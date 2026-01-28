package com.miniim.domain.cache;

import com.miniim.common.cache.CacheProperties;
import com.miniim.common.cache.RedisJsonCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 好友 id 集合缓存（仅存 id，不存用户信息）。
 *
 * <p>用途：朋友圈时间线/可见性判定等需要“高频读取好友集合”的场景。</p>
 */
@Slf4j
@Component
public class FriendIdsCache {

    private static final String KEY_PREFIX = "im:cache:friend:ids:";

    private final CacheProperties props;
    private final RedisJsonCache cache;

    public FriendIdsCache(CacheProperties props, RedisJsonCache cache) {
        this.props = props;
        this.cache = cache;
    }

    public Set<Long> get(long userId) {
        if (!props.isEnabled() || userId <= 0) {
            return null;
        }
        try {
            Value v = cache.get(key(userId), Value.class);
            if (v == null) {
                return null;
            }
            if (v.ids == null || v.ids.isEmpty()) {
                return Set.of();
            }
            Set<Long> out = new HashSet<>();
            for (Long id : v.ids) {
                if (id != null && id > 0) {
                    out.add(id);
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("redis friend ids cache get failed: userId={}, err={}", userId, e.toString());
            return null;
        }
    }

    public void put(long userId, Collection<Long> friendIds) {
        if (!props.isEnabled() || userId <= 0 || friendIds == null) {
            return;
        }
        try {
            List<Long> ids = new ArrayList<>();
            for (Long id : friendIds) {
                if (id != null && id > 0) {
                    ids.add(id);
                }
            }
            cache.set(key(userId), new Value(ids.stream().distinct().toList()), Duration.ofSeconds(Math.max(1, props.getFriendIdsTtlSeconds())));
        } catch (Exception e) {
            log.debug("redis friend ids cache put failed: userId={}, err={}", userId, e.toString());
        }
    }

    public void evict(long userId) {
        if (!props.isEnabled() || userId <= 0) {
            return;
        }
        try {
            cache.delete(key(userId));
        } catch (Exception e) {
            log.debug("redis friend ids cache evict failed: userId={}, err={}", userId, e.toString());
        }
    }

    private String key(long userId) {
        return KEY_PREFIX + userId;
    }

    public static class Value {
        public List<Long> ids;

        public Value() {
        }

        public Value(List<Long> ids) {
            this.ids = ids;
        }
    }
}
