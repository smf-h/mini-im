package com.miniim.domain.cache;

import com.miniim.common.cache.CacheProperties;
import com.miniim.common.cache.RedisJsonCache;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class UserProfileCache {

    private static final String KEY_PREFIX = "im:cache:user:profile:";

    private final CacheProperties props;
    private final RedisJsonCache cache;

    public UserProfileCache(CacheProperties props, RedisJsonCache cache) {
        this.props = props;
        this.cache = cache;
    }

    public Value get(long userId) {
        if (!props.isEnabled() || userId <= 0) {
            return null;
        }
        return cache.get(key(userId), Value.class);
    }

    public Map<Long, Value> getBatch(Collection<Long> userIds) {
        if (!props.isEnabled() || userIds == null || userIds.isEmpty()) {
            return new HashMap<>();
        }

        List<Long> ids = userIds.stream()
                .filter(v -> v != null && v > 0)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return new HashMap<>();
        }

        List<String> keys = ids.stream().map(this::key).toList();
        Map<String, Value> byKey = cache.mget(keys, Value.class);
        if (byKey.isEmpty()) {
            return new HashMap<>();
        }

        Map<Long, Value> out = new HashMap<>();
        for (Long id : ids) {
            Value v = byKey.get(key(id));
            if (v != null) {
                out.put(id, v);
            }
        }
        return out;
    }

    public void put(long userId, Value value) {
        if (!props.isEnabled() || userId <= 0 || value == null) {
            return;
        }
        cache.set(key(userId), value, Duration.ofSeconds(Math.max(1, props.getUserProfileTtlSeconds())));
    }

    public void evict(long userId) {
        if (!props.isEnabled() || userId <= 0) {
            return;
        }
        cache.delete(key(userId));
    }

    private String key(long userId) {
        return KEY_PREFIX + userId;
    }

    public record Value(
            Long id,
            String username,
            String nickname,
            String avatarUrl,
            Integer status,
            String friendCode,
            LocalDateTime friendCodeUpdatedAt
    ) {
    }
}
