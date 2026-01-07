package com.miniim.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
public class SessionVersionStore {

    private static final String KEY_PREFIX = "im:auth:sv:";
    private static final Duration DEFAULT_TTL = Duration.ofDays(90);

    private final StringRedisTemplate redis;

    public SessionVersionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * bump sessionVersion：用于“新登录使旧 accessToken 立即失效”。
     */
    public long bump(long userId) {
        if (userId <= 0) {
            return 0;
        }
        String key = key(userId);
        try {
            Long v = redis.opsForValue().increment(key);
            if (v == null) {
                return 0;
            }
            redis.expire(key, DEFAULT_TTL);
            return v;
        } catch (Exception e) {
            log.warn("bump sessionVersion failed, redis unavailable? userId={}, err={}", userId, e.toString());
            return 0;
        }
    }

    public long current(long userId) {
        if (userId <= 0) {
            return 0;
        }
        try {
            String raw = redis.opsForValue().get(key(userId));
            if (raw == null || raw.isBlank()) {
                return 0;
            }
            return Long.parseLong(raw.trim());
        } catch (Exception e) {
            log.debug("get sessionVersion failed: userId={}, err={}", userId, e.toString());
            return -1;
        }
    }

    public boolean isValid(long userId, long tokenSv) {
        if (userId <= 0) {
            return false;
        }
        long cur = current(userId);
        if (cur < 0) {
            // fail-open：Redis 异常时不阻断业务（回退为“仅校验 JWT 自身”）
            return true;
        }
        return cur == tokenSv;
    }

    private String key(long userId) {
        return KEY_PREFIX + userId;
    }
}
