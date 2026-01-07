package com.miniim.domain.cache;

import com.miniim.common.cache.CacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
public class SingleChatPairCache {

    private static final String KEY_PREFIX = "im:cache:single_chat:pair:";

    private final CacheProperties props;
    private final StringRedisTemplate redis;

    public SingleChatPairCache(CacheProperties props, StringRedisTemplate redis) {
        this.props = props;
        this.redis = redis;
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
        try {
            String raw = redis.opsForValue().get(key(a, b));
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return Long.parseLong(raw.trim());
        } catch (Exception e) {
            log.debug("singleChat pair cache get failed: u1={}, u2={}, err={}", user1Id, user2Id, e.toString());
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
        try {
            redis.opsForValue().set(
                    key(a, b),
                    String.valueOf(singleChatId),
                    Duration.ofSeconds(Math.max(1, props.getSingleChatPairTtlSeconds()))
            );
        } catch (Exception e) {
            log.debug("singleChat pair cache set failed: u1={}, u2={}, err={}", user1Id, user2Id, e.toString());
        }
    }

    private String key(long a, long b) {
        return KEY_PREFIX + a + ":" + b;
    }
}

