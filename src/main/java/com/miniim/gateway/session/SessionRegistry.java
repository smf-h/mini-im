package com.miniim.gateway.session;

import com.miniim.gateway.config.GatewayProperties;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionRegistry {

    public static final AttributeKey<Long> ATTR_USER_ID = AttributeKey.valueOf("uid");
    public static final AttributeKey<Long> ATTR_ACCESS_EXP_MS = AttributeKey.valueOf("aexp");

    private static final String ROUTE_KEY_PREFIX = "im:gw:route:";
    private static final Duration ROUTE_TTL = Duration.ofSeconds(120);

    /**
     * 单设备：本机内存只维护 userId -> Channel（当前实例的连接）。
     *
     * <p>注意：Channel 无法序列化到 Redis，所以多实例时只能把“路由信息”存 Redis：</p>
     * <ul>
     *   <li>Redis：userId -> instanceId（用于定位推送落到哪个实例）</li>
     *   <li>本机：userId -> Channel（用于在当前实例 close/写回）</li>
     * </ul>
     */
    private final ConcurrentHashMap<Long, Channel> userChannel = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> channelIdToUserId = new ConcurrentHashMap<>();

    private final StringRedisTemplate redis;
    private final String instanceId;

    public SessionRegistry(StringRedisTemplate redis, GatewayProperties wsProps) {
        this.redis = redis;
        this.instanceId = wsProps.host() + ":" + wsProps.port();
    }

    public void bind(Channel ch, long userId, Long accessExpMs) {
        Channel old = userChannel.put(userId, ch);
        if (old != null && old != ch) {
            unbind(old);
            old.close();
        }

        ch.attr(ATTR_USER_ID).set(userId);
        ch.attr(ATTR_ACCESS_EXP_MS).set(accessExpMs);
        channelIdToUserId.put(ch.id().asShortText(), userId);

        setRoute(userId);
    }

    public void unbind(Channel ch) {
        Long userId = ch.attr(ATTR_USER_ID).get();
        if (userId != null) {
            userChannel.remove(userId, ch);
            deleteRouteIfOwned(userId);
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
        return userChannel.get(userId);
    }

    public Collection<Channel> getAllChannels() {
        return userChannel.values();
    }

    public void touch(Channel ch) {
        Long userId = ch.attr(ATTR_USER_ID).get();
        if (userId != null) {
            setRoute(userId);
        }
    }

    private void setRoute(long userId) {
        redis.opsForValue().set(routeKey(userId), instanceId, ROUTE_TTL);
    }

    private void deleteRouteIfOwned(long userId) {
        String key = routeKey(userId);
        String cur = redis.opsForValue().get(key);
        if (instanceId.equals(cur)) {
            redis.delete(key);
        }
    }

    private String routeKey(long userId) {
        return ROUTE_KEY_PREFIX + userId;
    }
}
