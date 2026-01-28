package com.miniim.domain.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.miniim.common.cache.CacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class GroupMemberIdsCache {

    private static final String KEY_PREFIX = "im:cache:group:member_ids:";

    /**
     * Redis down 时的 fail-fast + 本机兜底：避免群聊主链路被 Redis 超时放大成“雪崩”。
     *
     * <p>限制：本机缓存不跨实例、不跨重启；但在 Redis down 场景下属于合理退化。</p>
     */
    private static final long REDIS_FAIL_FAST_MS = 10_000;
    private static final AtomicLong REDIS_UNAVAILABLE_UNTIL_MS = new AtomicLong(0);

    private static final int MAX_LOCAL_CACHE_GROUPS = 20_000;
    private static final int MAX_LOCAL_CACHE_MEMBER_IDS = 5_000;

    private final CacheProperties props;
    private final StringRedisTemplate redis;
    private final Cache<Long, Set<Long>> local;

    public GroupMemberIdsCache(CacheProperties props, StringRedisTemplate redis) {
        this.props = props;
        this.redis = redis;
        this.local = Caffeine.newBuilder()
                .maximumSize(MAX_LOCAL_CACHE_GROUPS)
                .expireAfterWrite(Duration.ofSeconds(Math.max(1, props.getGroupMemberIdsTtlSeconds())))
                .build();
    }

    public Set<Long> get(long groupId) {
        if (!props.isEnabled() || groupId <= 0) {
            return null;
        }

        Set<Long> localHit = local.getIfPresent(groupId);
        if (localHit != null && !localHit.isEmpty()) {
            return localHit;
        }

        if (shouldFailFast()) {
            return null;
        }
        try {
            Set<String> raw = redis.opsForSet().members(key(groupId));
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            Set<Long> out = new HashSet<>();
            for (String s : raw) {
                if (s == null || s.isBlank()) {
                    continue;
                }
                try {
                    long v = Long.parseLong(s.trim());
                    if (v > 0) {
                        out.add(v);
                    }
                } catch (NumberFormatException ignore) {
                    // Redis 集合中存在非数字脏数据：跳过该项
                }
            }
            if (out.isEmpty()) {
                return null;
            }
            if (out.size() <= MAX_LOCAL_CACHE_MEMBER_IDS) {
                Set<Long> frozen = Set.copyOf(out);
                local.put(groupId, frozen);
                return frozen;
            }
            return out;
        } catch (Exception e) {
            log.debug("redis group member ids cache get failed: groupId={}, err={}", groupId, e.toString());
            markRedisDown();
            return null;
        }
    }

    public void put(long groupId, Collection<Long> userIds) {
        if (!props.isEnabled() || groupId <= 0 || userIds == null || userIds.isEmpty()) {
            return;
        }

        if (userIds.size() <= MAX_LOCAL_CACHE_MEMBER_IDS) {
            Set<Long> out = new HashSet<>();
            for (Long v : userIds) {
                if (v != null && v > 0) {
                    out.add(v);
                }
            }
            if (!out.isEmpty()) {
                local.put(groupId, Set.copyOf(out));
            }
        }

        if (shouldFailFast()) {
            return;
        }
        String k = key(groupId);
        try {
            String[] values = userIds.stream()
                    .filter(v -> v != null && v > 0)
                    .map(String::valueOf)
                    .toArray(String[]::new);
            if (values.length == 0) {
                return;
            }
            redis.opsForSet().add(k, values);
            long ttlSec = Math.max(1, props.getGroupMemberIdsTtlSeconds());
            redis.expire(k, Duration.ofSeconds(ttlSec));
        } catch (Exception e) {
            log.debug("redis group member ids cache put failed: groupId={}, err={}", groupId, e.toString());
            markRedisDown();
        }
    }

    public void evict(long groupId) {
        if (!props.isEnabled() || groupId <= 0) {
            return;
        }
        local.invalidate(groupId);
        if (shouldFailFast()) {
            return;
        }
        try {
            redis.delete(key(groupId));
        } catch (Exception e) {
            log.debug("redis group member ids cache evict failed: groupId={}, err={}", groupId, e.toString());
            markRedisDown();
        }
    }

    private String key(long groupId) {
        return KEY_PREFIX + groupId;
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
