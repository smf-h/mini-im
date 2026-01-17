package com.miniim.gateway.ws;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.miniim.gateway.config.ClientMsgIdCaffeineProperties;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 客户端请求幂等（ClientMsgId）：用 (userId + biz + clientMsgId) 作为 key。
 *
 * <p>用于处理客户端重试：避免重复落库/重复投递。</p>
 */
@Component
@EnableConfigurationProperties(ClientMsgIdCaffeineProperties.class)
@Slf4j
public class ClientMsgIdIdempotency {

    private static final String REDIS_KEY_PREFIX = "im:idem:client_msg_id:";

    /**
     * Redis 故障时做 fail-fast：幂等只是保护阀，Redis 不可用时直接回退为本机 best-effort。
     */
    private static final long REDIS_FAIL_FAST_MS = 10_000;
    private static final AtomicLong REDIS_UNAVAILABLE_UNTIL_MS = new AtomicLong(0);

    @Getter
    private final ClientMsgIdCaffeineProperties props;
    @Data
    public static class Claim{
        String serverMsgId;
    }
    private final Cache<String, Claim> cache;
    private final StringRedisTemplate redis;

    public ClientMsgIdIdempotency(ClientMsgIdCaffeineProperties props, StringRedisTemplate redis) {
        this.props = props;
        this.redis = redis;
        this.cache = Caffeine.newBuilder()
                .initialCapacity(Math.max(1, props.getInitialCapacity()))
                .maximumSize(Math.max(1, props.getMaximumSize()))
                .expireAfterWrite(Duration.ofSeconds(Math.max(1, props.getExpireAfterAccessSeconds())))
                .build();
    }

    public String key(long userId, String biz, String clientMsgId) {
        String safeBiz = biz == null || biz.isBlank() ? "DEFAULT" : biz.trim();
        return userId + ":" + safeBiz + ":" + clientMsgId;
    }

    public Claim get(String key) {
        return cache.getIfPresent(key);
    }
    public void put(String key, Claim claim){
        cache.put(key, claim);
    }
    public Claim putIfAbsent(String key,Claim claim){
        if (!props.isEnabled()) {
            return null;
        }

        Claim local = cache.getIfPresent(key);
        if (local != null) {
            return local;
        }

        // Redis 优先（跨实例），失败则降级本机缓存。
        if (shouldFailFast()) {
            return cache.asMap().putIfAbsent(key, claim);
        }
        try {
            String redisKey = redisKey(key);
            boolean ok = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(redisKey, claim.getServerMsgId(), ttl()));
            if (ok) {
                cache.put(key, claim);
                return null;
            }

            String existedServerMsgId = redis.opsForValue().get(redisKey);
            if (existedServerMsgId != null && !existedServerMsgId.isBlank()) {
                Claim existed = new Claim();
                existed.setServerMsgId(existedServerMsgId);
                cache.put(key, existed);
                return existed;
            }
            // Redis 返回“已存在”但读不到值：降级为本机 putIfAbsent（best-effort）
            return cache.asMap().putIfAbsent(key, claim);
        } catch (Exception e) {
            log.debug("idem redis putIfAbsent failed: key={}, err={}", key, e.toString());
            markRedisDown();
            return cache.asMap().putIfAbsent(key, claim);
        }
    }
    public void remove(String key){
        cache.invalidate(key);
        if (!props.isEnabled()) {
            return;
        }
        if (shouldFailFast()) {
            return;
        }
        try {
            redis.delete(redisKey(key));
        } catch (Exception e) {
            log.debug("idem redis delete failed: key={}, err={}", key, e.toString());
            markRedisDown();
        }
    }

    private Duration ttl() {
        long sec = props.getExpireAfterAccessSeconds();
        if (sec <= 0) {
            sec = 1800;
        }
        return Duration.ofSeconds(sec);
    }

    private static String redisKey(String key) {
        return REDIS_KEY_PREFIX + key;
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
