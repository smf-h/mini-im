package com.miniim.gateway.ws;

import com.miniim.gateway.session.SessionRegistry;
import com.miniim.gateway.session.WsRouteStore;
import com.miniim.gateway.ws.cluster.WsClusterBus;
import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WsPushService {

    private final SessionRegistry sessionRegistry;
    private final WsRouteStore routeStore;
    private final WsClusterBus clusterBus;
    private final WsWriter wsWriter;

    public void pushToUser(long userId, WsEnvelope envelope) {
        boolean anyLocal = pushToUserLocalOnly(userId, envelope);
        if (anyLocal) {
            return;
        }

        WsRouteStore.RouteInfo route = routeStore.get(userId);
        if (route == null || route.serverId() == null || route.serverId().isBlank()) {
            return;
        }
        if (routeStore.serverId().equals(route.serverId())) {
            return;
        }
        clusterBus.publishPush(route.serverId(), userId, envelope);
    }

    public boolean pushToUserLocalOnly(long userId, WsEnvelope envelope) {
        List<Channel> channels = sessionRegistry.getChannels(userId);
        if (channels == null || channels.isEmpty()) {
            return false;
        }
        boolean pushed = false;
        for (Channel ch : channels) {
            pushed |= pushToChannel(ch, envelope);
        }
        return pushed;
    }

    public void pushToUsers(Collection<Long> userIds, WsEnvelope envelope) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        for (Long uid : userIds) {
            if (uid == null || uid <= 0) {
                continue;
            }
            pushToUser(uid, envelope);
        }
    }

    private boolean pushToChannel(Channel ch, WsEnvelope envelope) {
        if (ch == null || !ch.isActive()) {
            return false;
        }
        wsWriter.write(ch, envelope).addListener(f -> {
            if (!f.isSuccess()) {
                log.debug("push failed: {}", f.cause() == null ? "unknown" : f.cause().toString());
            }
        });
        return true;
    }
}
