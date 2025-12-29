package com.miniim.gateway.session;

import com.miniim.gateway.config.GatewayProperties;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class SessionRegistry {

    public static final AttributeKey<Long> ATTR_USER_ID = AttributeKey.valueOf("uid");
    public static final AttributeKey<Long> ATTR_ACCESS_EXP_MS = AttributeKey.valueOf("aexp");

    private static final String ROUTE_KEY_PREFIX = "im:gw:route:";
    private static final Duration ROUTE_TTL = Duration.ofSeconds(120);

    /**
     * 本机内存维护 userId -> Channel 集合（当前实例的连接）。
     *
     * <p>注意：Channel 无法序列化到 Redis，所以多实例时只能把“路由信息”存 Redis：</p>
     * <ul>
     *   <li>Redis：userId -> instanceId（用于定位推送落到哪个实例）</li>
     *   <li>本机：userId -> Channel(s)（用于在当前实例 close/写回）</li>
     * </ul>
     */
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, Channel>> userChannels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> channelIdToUserId = new ConcurrentHashMap<>();

    private final StringRedisTemplate redis;
    private final String instanceId;

    public SessionRegistry(StringRedisTemplate redis, GatewayProperties wsProps) {
        this.redis = redis;
        this.instanceId = wsProps.host() + ":" + wsProps.port();
    }

    public void bind(Channel ch, long userId, Long accessExpMs) {
        userChannels.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                .put(ch.id().asShortText(), ch);

        ch.attr(ATTR_USER_ID).set(userId);
        ch.attr(ATTR_ACCESS_EXP_MS).set(accessExpMs);
        channelIdToUserId.put(ch.id().asShortText(), userId);

        setRoute(userId);
    }

    public void unbind(Channel ch) {
        Long userId = ch.attr(ATTR_USER_ID).get();
        if (userId != null) {
            ConcurrentHashMap<String, Channel> map = userChannels.get(userId);
            if (map != null) {
                map.remove(ch.id().asShortText(), ch);
                if (map.isEmpty()) {
                    userChannels.remove(userId, map);
                    deleteRouteIfOwned(userId);
                }
            }
        }
        channelIdToUserId.remove(ch.id().asShortText());
    }

    public boolean isAuthed(Channel ch) {
        return ch.attr(ATTR_USER_ID).get() != null;
    }

    public Long getAccessExpMs(Channel ch) {
        return ch.attr(ATTR_ACCESS_EXP_MS).get();
    }

    public Channel getChannel(long userId) {
        ConcurrentHashMap<String, Channel> map = userChannels.get(userId);
        if (map == null || map.isEmpty()) {
            return null;
        }
        for (Channel ch : map.values()) {
            if (ch != null && ch.isActive()) {
                return ch;
            }
        }
        return null;
    }

    public List<Channel> getChannels(long userId) {
        ConcurrentHashMap<String, Channel> map = userChannels.get(userId);
        if (map == null || map.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(map.values());
    }

    public Collection<Channel> getAllChannels() {
        List<Channel> all = new ArrayList<>();
        for (Map<String, Channel> map : userChannels.values()) {
            all.addAll(map.values());
        }
        return all;
    }

    public List<Long> getOnlineUserIds() {
        List<Long> userIds = new ArrayList<>();
        for (Map.Entry<Long, ConcurrentHashMap<String, Channel>> entry : userChannels.entrySet()) {
            if (entry == null) {
                continue;
            }
            ConcurrentHashMap<String, Channel> map = entry.getValue();
            if (map == null || map.isEmpty()) {
                continue;
            }
            boolean anyActive = false;
            for (Channel ch : map.values()) {
                if (ch != null && ch.isActive()) {
                    anyActive = true;
                    break;
                }
            }
            if (anyActive) {
                userIds.add(entry.getKey());
            }
        }
        return userIds;
    }

    public void touch(Channel ch) {
        Long userId = ch.attr(ATTR_USER_ID).get();
        if (userId != null) {
            setRoute(userId);
        }
    }

    private void setRoute(long userId) {
        try {
            redis.opsForValue().set(routeKey(userId), instanceId, ROUTE_TTL);
        } catch (Exception e) {
            // 开发/单机模式：Redis 不可用时允许降级（仅维持本机 userId -> Channel 映射）
            log.warn("setRoute failed, redis unavailable? userId={}, instanceId={}, err={}", userId, instanceId, e.toString());
        }
    }

    private void deleteRouteIfOwned(long userId) {
        String key = routeKey(userId);
        try {
            String cur = redis.opsForValue().get(key);
            if (instanceId.equals(cur)) {
                redis.delete(key);
            }
        } catch (Exception e) {
            log.warn("deleteRouteIfOwned failed, redis unavailable? userId={}, instanceId={}, err={}", userId, instanceId, e.toString());
        }
    }

    private String routeKey(long userId) {
        return ROUTE_KEY_PREFIX + userId;
    }
}
