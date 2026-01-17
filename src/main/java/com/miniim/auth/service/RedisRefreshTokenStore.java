package com.miniim.auth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final String KEY_PREFIX = "im:refresh:";
    private static final String KEY_USER_PREFIX = "im:refresh:user:";

    /**
     * Redis 不可用时的本机兜底：保证登录/刷新接口不被 Redis 宕机直接打挂。
     *
     * <p>限制：本机缓存不跨实例、不跨重启；但在 Redis down 场景下属于“可用性优先”的合理退化。</p>
     */
    private static final Duration LOCAL_TTL_FALLBACK = Duration.ofDays(30);

    private static final long REDIS_FAIL_FAST_MS = 10_000;
    private static final AtomicLong REDIS_UNAVAILABLE_UNTIL_MS = new AtomicLong(0);

    private final StringRedisTemplate redis;
    private final Cache<String, RefreshSession> localTokenCache;
    private final Cache<Long, String> localUserTokenCache;

    public RedisRefreshTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
        this.localTokenCache = Caffeine.newBuilder()
                .maximumSize(200_000)
                .expireAfterWrite(LOCAL_TTL_FALLBACK)
                .build();
        this.localUserTokenCache = Caffeine.newBuilder()
                .maximumSize(200_000)
                .expireAfterWrite(LOCAL_TTL_FALLBACK)
                .build();
    }

    @Override
    public void put(String tokenHash, RefreshSession session, Duration ttl) {
        // 单设备（单用户仅保留一个 refreshToken）：
        // - 以 userId 维度记录“当前有效 tokenHash”
        // - 新 token 写入时，自动删除旧 tokenHash 对应的会话 key
        if (!shouldFailFast()) {
            try {
                String userKey = userKey(session.userId());
                String oldHash = redis.opsForValue().get(userKey);
                if (oldHash != null && !oldHash.isBlank() && !oldHash.equals(tokenHash)) {
                    redis.delete(key(oldHash));
                }

                String key = key(tokenHash);
                // value 格式：userId
                String value = String.valueOf(session.userId());
                redis.opsForValue().set(key, value, ttl);

                // userId -> tokenHash 映射（用于覆盖旧 token）
                redis.opsForValue().set(userKey, tokenHash, ttl);
                return;
            } catch (Exception e) {
                markRedisDown();
                log.warn("refresh token redis put failed, fallback to local: userId={}, err={}", session.userId(), e.toString());
            }
        }

        // fallback: local only
        String oldHash = localUserTokenCache.getIfPresent(session.userId());
        if (oldHash != null && !oldHash.isBlank() && !oldHash.equals(tokenHash)) {
            localTokenCache.invalidate(oldHash);
        }
        localTokenCache.put(tokenHash, session);
        localUserTokenCache.put(session.userId(), tokenHash);
    }

    @Override
    public RefreshSession get(String tokenHash) {
        if (!shouldFailFast()) {
            try {
                String value = redis.opsForValue().get(key(tokenHash));
                if (value == null || value.isBlank()) {
                    return null;
                }
                long userId = Long.parseLong(value.trim());
                return new RefreshSession(userId);
            } catch (Exception e) {
                markRedisDown();
                log.debug("refresh token redis get failed, fallback to local: err={}", e.toString());
            }
        }
        return localTokenCache.getIfPresent(tokenHash);
    }

    @Override
    public void touch(String tokenHash, Duration ttl) {
        if (!shouldFailFast()) {
            try {
                redis.expire(key(tokenHash), ttl);

                RefreshSession session = get(tokenHash);
                if (session != null) {
                    redis.expire(userKey(session.userId()), ttl);
                }
                return;
            } catch (Exception e) {
                markRedisDown();
                log.debug("refresh token redis touch failed, fallback to local: err={}", e.toString());
            }
        }

        RefreshSession session = localTokenCache.getIfPresent(tokenHash);
        if (session != null) {
            localTokenCache.put(tokenHash, session);
            localUserTokenCache.put(session.userId(), tokenHash);
        }
    }

    @Override
    public void delete(String tokenHash) {
        if (!shouldFailFast()) {
            try {
                RefreshSession session = get(tokenHash);
                redis.delete(key(tokenHash));

                if (session != null) {
                    String userKey = userKey(session.userId());
                    String currentHash = redis.opsForValue().get(userKey);
                    if (tokenHash.equals(currentHash)) {
                        redis.delete(userKey);
                    }
                }
                return;
            } catch (Exception e) {
                markRedisDown();
                log.debug("refresh token redis delete failed, fallback to local: err={}", e.toString());
            }
        }

        RefreshSession session = localTokenCache.getIfPresent(tokenHash);
        localTokenCache.invalidate(tokenHash);
        if (session != null) {
            String currentHash = localUserTokenCache.getIfPresent(session.userId());
            if (tokenHash.equals(currentHash)) {
                localUserTokenCache.invalidate(session.userId());
            }
        }
    }

    private String key(String tokenHash) {
        return KEY_PREFIX + tokenHash;
    }

    private String userKey(long userId) {
        return KEY_USER_PREFIX + userId;
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
