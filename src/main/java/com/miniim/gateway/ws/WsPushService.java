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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
@RequiredArgsConstructor
public class WsPushService {

    private final SessionRegistry sessionRegistry;
    private final WsRouteStore routeStore;
    private final WsClusterBus clusterBus;
    private final WsWriter wsWriter;
    private final com.miniim.gateway.config.WsBackpressureProperties backpressureProps;
    private final com.miniim.gateway.config.WsPerfTraceProperties perfTraceProps;

    public WsWriter.PreparedBytes prepareBytes(WsEnvelope envelope) {
        return wsWriter.prepareBytes(envelope);
    }

    public void pushToUser(long userId, WsEnvelope envelope) {
        long startNs = System.nanoTime();
        boolean anyLocal = pushToUserLocalOnly(userId, envelope);
        if (anyLocal) {
            maybeLogPerf(startNs, true, userId, envelope, true, 0L, 0L);
            return;
        }

        long routeStartNs = System.nanoTime();
        WsRouteStore.RouteInfo route = routeStore.get(userId);
        long routeNs = System.nanoTime() - routeStartNs;
        if (route == null || route.serverId() == null || route.serverId().isBlank()) {
            maybeLogPerf(startNs, false, userId, envelope, false, routeNs, 0L);
            return;
        }
        if (routeStore.serverId().equals(route.serverId())) {
            maybeLogPerf(startNs, false, userId, envelope, false, routeNs, 0L);
            return;
        }
        long pubStartNs = System.nanoTime();
        clusterBus.publishPush(route.serverId(), userId, envelope);
        long pubNs = System.nanoTime() - pubStartNs;
        maybeLogPerf(startNs, true, userId, envelope, false, routeNs, pubNs);
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

    public boolean pushToUserLocalOnly(long userId, WsEnvelope envelope, WsWriter.PreparedBytes prepared) {
        List<Channel> channels = sessionRegistry.getChannels(userId);
        if (channels == null || channels.isEmpty()) {
            return false;
        }
        boolean pushed = false;
        for (Channel ch : channels) {
            pushed |= pushToChannel(ch, envelope, prepared);
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
        if (backpressureProps != null
                && backpressureProps.enabledEffective()
                && backpressureProps.dropWhenUnwritableEffective()
                && !ch.isWritable()) {
            if (isCritical(envelope)) {
                Long uid = ch.attr(SessionRegistry.ATTR_USER_ID).get();
                String cid = ch.attr(SessionRegistry.ATTR_CONN_ID).get();
                log.warn("ws backpressure: closing unwritable channel for critical push: type={}, uid={}, cid={}",
                        envelope == null ? null : envelope.type, uid, cid);
                try {
                    ch.close();
                } catch (Exception e) {
                    log.debug("close channel failed: {}", e.toString());
                }
            }
            return false;
        }
        wsWriter.write(ch, envelope).addListener(f -> {
            if (!f.isSuccess()) {
                log.debug("push failed: {}", f.cause() == null ? "unknown" : f.cause().toString());
            }
        });
        return true;
    }

    private boolean pushToChannel(Channel ch, WsEnvelope envelope, WsWriter.PreparedBytes prepared) {
        if (ch == null || !ch.isActive()) {
            return false;
        }
        if (backpressureProps != null
                && backpressureProps.enabledEffective()
                && backpressureProps.dropWhenUnwritableEffective()
                && !ch.isWritable()) {
            if (isCritical(envelope)) {
                Long uid = ch.attr(SessionRegistry.ATTR_USER_ID).get();
                String cid = ch.attr(SessionRegistry.ATTR_CONN_ID).get();
                log.warn("ws backpressure: closing unwritable channel for critical push: type={}, uid={}, cid={}",
                        envelope == null ? null : envelope.type, uid, cid);
                try {
                    ch.close();
                } catch (Exception e) {
                    log.debug("close channel failed: {}", e.toString());
                }
            }
            return false;
        }
        wsWriter.writePreparedBytes(ch, envelope, prepared).addListener(f -> {
            if (!f.isSuccess()) {
                log.debug("push failed: {}", f.cause() == null ? "unknown" : f.cause().toString());
            }
        });
        return true;
    }

    private void maybeLogPerf(long startNs,
                              boolean ok,
                              long toUserId,
                              WsEnvelope envelope,
                              boolean local,
                              long routeNs,
                              long publishNs) {
        if (perfTraceProps == null || !perfTraceProps.enabledEffective()) {
            return;
        }
        long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        long slowMs = perfTraceProps.slowMsEffective();
        double sampleRate = perfTraceProps.sampleRateEffective();
        boolean sampled = sampleRate > 0 && ThreadLocalRandom.current().nextDouble() < sampleRate;
        if (totalMs < slowMs && !sampled) {
            return;
        }
        String type = envelope == null ? null : envelope.type;
        Long from = envelope == null ? null : envelope.from;
        String serverMsgId = envelope == null ? null : envelope.serverMsgId;
        Long groupId = envelope == null ? null : envelope.groupId;
        log.info("ws_perf push ok={} type={} from={} to={} groupId={} serverMsgId={} local={} totalMs={} routeMs={} redisPubMs={}",
                ok, type, from, toUserId, groupId, serverMsgId, local,
                totalMs,
                TimeUnit.NANOSECONDS.toMillis(routeNs),
                TimeUnit.NANOSECONDS.toMillis(publishNs));
    }

    private static boolean isCritical(WsEnvelope envelope) {
        if (envelope == null || envelope.type == null || envelope.type.isBlank()) {
            return false;
        }
        String t = envelope.type;
        return "ERROR".equalsIgnoreCase(t) || t.startsWith("CALL_");
    }
}
