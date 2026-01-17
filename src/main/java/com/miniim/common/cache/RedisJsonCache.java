package com.miniim.common.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisJsonCache {

    /**
     * Redis 故障时做 fail-fast：避免每次 cache 读写都阻塞在 Redis 连接/超时上，放大尾延迟。
     *
     * <p>注意：这是“可用性优先”的折中——短时间内缓存能力下降（本来 Redis 已不可用）。</p>
     */
    private static final long REDIS_FAIL_FAST_MS = 10_000;
    private static final AtomicLong REDIS_UNAVAILABLE_UNTIL_MS = new AtomicLong(0);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public <T> T get(String key, Class<T> type) {
        if (key == null || key.isBlank() || type == null) {
            return null;
        }
        if (shouldFailFast()) {
            return null;
        }
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return objectMapper.readValue(raw, type);
        } catch (Exception e) {
            log.debug("redis json cache get failed: key={}, err={}", key, e.toString());
            markRedisDown();
            return null;
        }
    }

    public <T> Map<String, T> mget(List<String> keys, Class<T> type) {
        if (keys == null || keys.isEmpty() || type == null) {
            return Map.of();
        }
        if (shouldFailFast()) {
            return Map.of();
        }
        try {
            List<String> raws = redis.opsForValue().multiGet(keys);
            if (raws == null || raws.isEmpty()) {
                return Map.of();
            }
            Map<String, T> out = new LinkedHashMap<>();
            int n = Math.min(keys.size(), raws.size());
            for (int i = 0; i < n; i++) {
                String key = keys.get(i);
                if (key == null || key.isBlank()) {
                    continue;
                }
                String raw = raws.get(i);
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                try {
                    out.put(key, objectMapper.readValue(raw, type));
                } catch (Exception e) {
                    log.debug("redis json cache mget parse failed: key={}, err={}", key, e.toString());
                }
            }
            return out.isEmpty() ? Map.of() : out;
        } catch (Exception e) {
            log.debug("redis json cache mget failed: size={}, err={}", keys.size(), e.toString());
            markRedisDown();
            return Map.of();
        }
    }

    public void set(String key, Object value, Duration ttl) {
        if (key == null || key.isBlank() || value == null) {
            return;
        }
        if (shouldFailFast()) {
            return;
        }
        long sec = ttl == null ? 0 : ttl.toSeconds();
        if (sec <= 0) {
            sec = 60;
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            redis.opsForValue().set(key, json, Duration.ofSeconds(sec));
        } catch (Exception e) {
            log.debug("redis json cache set failed: key={}, err={}", key, e.toString());
            markRedisDown();
        }
    }

    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (shouldFailFast()) {
            return;
        }
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.debug("redis json cache delete failed: key={}, err={}", key, e.toString());
            markRedisDown();
        }
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
