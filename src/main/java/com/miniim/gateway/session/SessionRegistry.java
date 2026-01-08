package com.miniim.gateway.session;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

@Slf4j
@Component
public class SessionRegistry {

    public static final AttributeKey<Long> ATTR_USER_ID = AttributeKey.valueOf("uid");
    public static final AttributeKey<Long> ATTR_ACCESS_EXP_MS = AttributeKey.valueOf("aexp");
    public static final AttributeKey<String> ATTR_CONN_ID = AttributeKey.valueOf("cid");
    public static final AttributeKey<Long> ATTR_SESSION_VERSION = AttributeKey.valueOf("sv");
    public static final AttributeKey<Long> ATTR_LAST_SV_CHECK_MS = AttributeKey.valueOf("sv_chk_ms");

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

    private final WsRouteStore routeStore;

    public SessionRegistry(WsRouteStore routeStore) {
        this.routeStore = routeStore;
    }

    public String serverId() {
        return routeStore.serverId();
    }

    public String bind(Channel ch, long userId, Long accessExpMs) {
        return bind(ch, userId, accessExpMs, null);
    }

    public String bind(Channel ch, long userId, Long accessExpMs, Long sessionVersion) {
        String connId = ensureConnId(ch);
        userChannels.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                .put(ch.id().asShortText(), ch);

        ch.attr(ATTR_USER_ID).set(userId);
        ch.attr(ATTR_ACCESS_EXP_MS).set(accessExpMs);
        if (sessionVersion != null) {
            ch.attr(ATTR_SESSION_VERSION).set(sessionVersion);
        }
        channelIdToUserId.put(ch.id().asShortText(), userId);
        return connId;
    }

    public void unbind(Channel ch) {
        Long userId = ch.attr(ATTR_USER_ID).get();
        if (userId != null) {
            ConcurrentHashMap<String, Channel> map = userChannels.get(userId);
            if (map != null) {
                map.remove(ch.id().asShortText(), ch);
                if (map.isEmpty()) {
                    userChannels.remove(userId, map);
                    deleteRouteIfOwned(userId, ch.attr(ATTR_CONN_ID).get());
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
        String connId = ch.attr(ATTR_CONN_ID).get();
        if (userId != null) {
            expireRouteIfOwned(userId, connId);
        }
    }

    public void closeByConnId(long userId, String connId) {
        if (userId <= 0 || connId == null || connId.isBlank()) {
            return;
        }
        for (Channel ch : getChannels(userId)) {
            if (ch == null || !ch.isActive()) {
                continue;
            }
            String cid = ch.attr(ATTR_CONN_ID).get();
            if (connId.equals(cid)) {
                try {
                    ch.close();
                } catch (Exception e) {
                    log.debug("close channel failed: userId={}, err={}", userId, e.toString());
                }
            }
        }
    }

    public void closeOtherChannels(long userId, Channel keep) {
        if (userId <= 0) {
            return;
        }
        String keepId = keep == null ? null : keep.id().asShortText();
        for (Channel ch : getChannels(userId)) {
            if (ch == null || !ch.isActive()) {
                continue;
            }
            if (keepId != null && keepId.equals(ch.id().asShortText())) {
                continue;
            }
            try {
                ch.close();
            } catch (Exception e) {
                log.debug("close channel failed: userId={}, err={}", userId, e.toString());
            }
        }
    }

    private void expireRouteIfOwned(long userId, String connId) {
        if (userId <= 0 || connId == null || connId.isBlank()) {
            return;
        }
        routeStore.expireIfMatch(userId, connId, ROUTE_TTL);
    }

    private void deleteRouteIfOwned(long userId, String connId) {
        if (userId <= 0 || connId == null || connId.isBlank()) {
            return;
        }
        routeStore.deleteIfMatch(userId, connId);
    }

    private static String ensureConnId(Channel ch) {
        if (ch == null) {
            return null;
        }
        String existing = ch.attr(ATTR_CONN_ID).get();
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        ch.attr(ATTR_CONN_ID).set(id);
        return id;
    }
}
