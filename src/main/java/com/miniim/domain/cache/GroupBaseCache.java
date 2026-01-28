package com.miniim.domain.cache;

import com.miniim.common.cache.CacheProperties;
import com.miniim.common.cache.RedisJsonCache;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class GroupBaseCache {

    private static final String KEY_PREFIX = "im:cache:group:base:";

    private final CacheProperties props;
    private final RedisJsonCache cache;

    public GroupBaseCache(CacheProperties props, RedisJsonCache cache) {
        this.props = props;
        this.cache = cache;
    }

    public Value get(long groupId) {
        if (!props.isEnabled() || groupId <= 0) {
            return null;
        }
        return cache.get(key(groupId), Value.class);
    }

    public void put(long groupId, Value value) {
        if (!props.isEnabled() || groupId <= 0 || value == null) {
            return;
        }
        cache.set(key(groupId), value, Duration.ofSeconds(Math.max(1, props.getGroupBaseTtlSeconds())));
    }

    public void evict(long groupId) {
        if (!props.isEnabled() || groupId <= 0) {
            return;
        }
        cache.delete(key(groupId));
    }

    private String key(long groupId) {
        return KEY_PREFIX + groupId;
    }

    public record Value(
            Long groupId,
            String name,
            String avatarUrl,
            String groupCode,
            Long createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            Long memberCount
    ) {
    }
}

