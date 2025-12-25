package com.miniim.auth.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final String KEY_PREFIX = "im:refresh:";
    private static final String KEY_USER_PREFIX = "im:refresh:user:";

    private final StringRedisTemplate redis;

    public RedisRefreshTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void put(String tokenHash, RefreshSession session, Duration ttl) {
        // 单设备（单用户仅保留一个 refreshToken）：
        // - 以 userId 维度记录“当前有效 tokenHash”
        // - 新 token 写入时，自动删除旧 tokenHash 对应的会话 key
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
    }

    @Override
    public RefreshSession get(String tokenHash) {
        String value = redis.opsForValue().get(key(tokenHash));
        if (value == null || value.isBlank()) {
            return null;
        }
        long userId = Long.parseLong(value.trim());
        return new RefreshSession(userId);
    }

    @Override
    public void touch(String tokenHash, Duration ttl) {
        redis.expire(key(tokenHash), ttl);

        RefreshSession session = get(tokenHash);
        if (session != null) {
            redis.expire(userKey(session.userId()), ttl);
        }
    }

    @Override
    public void delete(String tokenHash) {
        RefreshSession session = get(tokenHash);
        redis.delete(key(tokenHash));

        if (session != null) {
            String userKey = userKey(session.userId());
            String currentHash = redis.opsForValue().get(userKey);
            if (tokenHash.equals(currentHash)) {
                redis.delete(userKey);
            }
        }
    }

    private String key(String tokenHash) {
        return KEY_PREFIX + tokenHash;
    }

    private String userKey(long userId) {
        return KEY_USER_PREFIX + userId;
    }
}
