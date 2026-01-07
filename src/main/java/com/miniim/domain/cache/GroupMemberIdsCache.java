package com.miniim.domain.cache;

import com.miniim.common.cache.CacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Component
public class GroupMemberIdsCache {

    private static final String KEY_PREFIX = "im:cache:group:member_ids:";

    private final CacheProperties props;
    private final StringRedisTemplate redis;

    public GroupMemberIdsCache(CacheProperties props, StringRedisTemplate redis) {
        this.props = props;
        this.redis = redis;
    }

    public Set<Long> get(long groupId) {
        if (!props.isEnabled() || groupId <= 0) {
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
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            log.debug("redis group member ids cache get failed: groupId={}, err={}", groupId, e.toString());
            return null;
        }
    }

    public void put(long groupId, Collection<Long> userIds) {
        if (!props.isEnabled() || groupId <= 0 || userIds == null || userIds.isEmpty()) {
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
        }
    }

    public void evict(long groupId) {
        if (!props.isEnabled() || groupId <= 0) {
            return;
        }
        try {
            redis.delete(key(groupId));
        } catch (Exception e) {
            log.debug("redis group member ids cache evict failed: groupId={}, err={}", groupId, e.toString());
        }
    }

    private String key(long groupId) {
        return KEY_PREFIX + groupId;
    }
}
