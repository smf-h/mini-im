package com.miniim.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class SessionVersionStore {

    private static final String KEY_PREFIX = "im:auth:sv:";
    private static final Duration DEFAULT_TTL = Duration.ofDays(90);

    /**
     * Redis 故障时做 fail-fast：避免鉴权链路被 Redis 超时放大尾延迟。
     *
     * <p>此处采用 fail-open：Redis 不可用时只校验 JWT 自身。</p>
     */
    private static final long REDIS_FAIL_FAST_MS = 10_000;
    private static final AtomicLong REDIS_UNAVAILABLE_UNTIL_MS = new AtomicLong(0);

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
        if (shouldFailFast()) {
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
            markRedisDown();
            return 0;
        }
    }

    public long current(long userId) {
        if (userId <= 0) {
            return 0;
        }
        if (shouldFailFast()) {
            return -1;
        }
        try {
            String raw = redis.opsForValue().get(key(userId));
            if (raw == null || raw.isBlank()) {
                return 0;
            }
            return Long.parseLong(raw.trim());
        } catch (Exception e) {
            log.debug("get sessionVersion failed: userId={}, err={}", userId, e.toString());
            markRedisDown();
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
