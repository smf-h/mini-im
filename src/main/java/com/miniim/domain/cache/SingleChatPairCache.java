package com.miniim.domain.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.miniim.common.cache.CacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class SingleChatPairCache {

    private static final String KEY_PREFIX = "im:cache:single_chat:pair:";

    /**
     * Redis down 时的本机兜底缓存：避免“每条消息都回源 DB 查 singleChatId”。
     *
     * <p>限制：本机缓存不跨实例、不跨重启；但在 Redis down 场景下属于合理退化。</p>
     */
    private static final long REDIS_FAIL_FAST_MS = 10_000;
    private static final AtomicLong REDIS_UNAVAILABLE_UNTIL_MS = new AtomicLong(0);

    private final CacheProperties props;
    private final StringRedisTemplate redis;
    private final Cache<String, Long> local;

    public SingleChatPairCache(CacheProperties props, StringRedisTemplate redis) {
        this.props = props;
        this.redis = redis;
        this.local = Caffeine.newBuilder()
                .maximumSize(200_000)
                .expireAfterWrite(Duration.ofSeconds(Math.max(1, props.getSingleChatPairTtlSeconds())))
                .build();
    }

    public Long get(long user1Id, long user2Id) {
        if (!props.isEnabled()) {
            return null;
        }
        long a = Math.min(user1Id, user2Id);
        long b = Math.max(user1Id, user2Id);
        if (a <= 0 || b <= 0 || a == b) {
            return null;
        }

        String localKey = localKey(a, b);
        Long localHit = local.getIfPresent(localKey);
        if (localHit != null && localHit > 0) {
            return localHit;
        }

        if (shouldFailFast()) {
            return null;
        }
        try {
            String raw = redis.opsForValue().get(key(a, b));
            if (raw == null || raw.isBlank()) {
                return null;
            }
            long id = Long.parseLong(raw.trim());
            if (id > 0) {
                local.put(localKey, id);
            }
            return id;
        } catch (Exception e) {
            log.debug("singleChat pair cache get failed: u1={}, u2={}, err={}", user1Id, user2Id, e.toString());
            markRedisDown();
            return null;
        }
    }

    public void put(long user1Id, long user2Id, long singleChatId) {
        if (!props.isEnabled()) {
            return;
        }
        long a = Math.min(user1Id, user2Id);
        long b = Math.max(user1Id, user2Id);
        if (a <= 0 || b <= 0 || a == b || singleChatId <= 0) {
            return;
        }

        local.put(localKey(a, b), singleChatId);
        if (shouldFailFast()) {
            return;
        }
        try {
            redis.opsForValue().set(
                    key(a, b),
                    String.valueOf(singleChatId),
                    Duration.ofSeconds(Math.max(1, props.getSingleChatPairTtlSeconds()))
            );
        } catch (Exception e) {
            log.debug("singleChat pair cache set failed: u1={}, u2={}, err={}", user1Id, user2Id, e.toString());
            markRedisDown();
        }
    }

    private String key(long a, long b) {
        return KEY_PREFIX + a + ":" + b;
    }

    private static String localKey(long a, long b) {
        return a + ":" + b;
    }

    private static boolean shouldFailFast() {
        return System.currentTimeMillis() < REDIS_UNAVAILABLE_UNTIL_MS.get();
    }

    private static void markRedisDown() {
        long until = System.currentTimeMillis() + REDIS_FAIL_FAST_MS;
        while (true) {
            long prev = REDIS_UNAVAILABLE_UNTIL_MS.get();
            if (prev >= until) {
                return;
            }
            if (REDIS_UNAVAILABLE_UNTIL_MS.compareAndSet(prev, until)) {
                return;
            }
        }
    }
}
