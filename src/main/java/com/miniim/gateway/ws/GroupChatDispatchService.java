package com.miniim.gateway.ws;

import com.miniim.gateway.config.GroupChatStrategyProperties;
import com.miniim.gateway.config.GroupFanoutProperties;
import com.miniim.gateway.session.WsRouteStore;
import com.miniim.gateway.ws.cluster.WsClusterBus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 群聊下发：按“群规模 + 在线人数”自动选择策略，并在策略1/2下使用“按实例分组批量 PUSH”。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties({GroupFanoutProperties.class, GroupChatStrategyProperties.class})
public class GroupChatDispatchService {

    enum Mode {
        AUTO,
        PUSH,   // 策略1：推消息体
        NOTIFY, // 策略2：推通知，客户端拉取
        NONE    // 不推
    }

    private final WsRouteStore routeStore;
    private final WsClusterBus clusterBus;
    private final WsPushService wsPushService;
    private final GroupFanoutProperties fanoutProps;
    private final GroupChatStrategyProperties strategyProps;

    public void dispatch(Set<Long> memberIds,
                         long fromUserId,
                         Set<Long> importantTargets,
                         WsEnvelope normalMessage,
                         WsEnvelope importantMessage,
                         WsEnvelope normalNotify,
                         WsEnvelope importantNotify) {
        long startNs = System.nanoTime();
        if (memberIds == null || memberIds.isEmpty()) {
            return;
        }

        int groupSize = memberIds.size();
        Mode mode = parseMode(strategyProps.getMode());

        if ((mode == Mode.AUTO || mode == Mode.NOTIFY) && groupSize >= safePositive(strategyProps.getHugeGroupNoNotifySize(), 10000)) {
            return;
        }
        if (mode == Mode.NONE) {
            return;
        }

        if (!fanoutProps.isEnabled()) {
            // 兼容关闭开关时的退化行为：逐成员 push（仍会命中 per-user route 查找）
            legacyPush(memberIds, fromUserId, importantTargets, mode == Mode.NOTIFY ? normalNotify : normalMessage, mode == Mode.NOTIFY ? importantNotify : importantMessage);
            return;
        }

        long routeStartNs = System.nanoTime();
        Routing routing = routeAndGroup(memberIds, fromUserId, importantTargets);
        long routeNs = System.nanoTime() - routeStartNs;
        if (!routing.ok) {
            if (fanoutProps.isFailOpen()) {
                legacyPush(memberIds, fromUserId, importantTargets, mode == Mode.NOTIFY ? normalNotify : normalMessage, mode == Mode.NOTIFY ? importantNotify : importantMessage);
            }
            return;
        }

        int onlineUserCount = routing.onlineUserCount;

        if (mode == Mode.AUTO) {
            int groupSizeThreshold = safePositive(strategyProps.getGroupSizeThreshold(), 2000);
            int onlineThreshold = safePositive(strategyProps.getOnlineUserThreshold(), 500);
            if (groupSize >= groupSizeThreshold || onlineUserCount >= onlineThreshold) {
                mode = Mode.NOTIFY;
            } else {
                mode = Mode.PUSH;
            }
        }

        if (mode == Mode.NOTIFY) {
            int maxNotifyOnline = safePositive(strategyProps.getNotifyMaxOnlineUser(), 2000);
            if (onlineUserCount >= maxNotifyOnline) {
                return;
            }
        }

        if (mode == Mode.PUSH) {
            long fanoutStartNs = System.nanoTime();
            fanoutByServer(routing.normalByServer, normalMessage);
            fanoutByServer(routing.importantByServer, importantMessage);
            long fanoutNs = System.nanoTime() - fanoutStartNs;
        } else if (mode == Mode.NOTIFY) {
            long fanoutStartNs = System.nanoTime();
            fanoutByServer(routing.normalByServer, normalNotify);
            fanoutByServer(routing.importantByServer, importantNotify);
            long fanoutNs = System.nanoTime() - fanoutStartNs;
        }
    }

    private void legacyPush(Set<Long> memberIds, long fromUserId, Set<Long> importantTargets, WsEnvelope normalEnv, WsEnvelope importantEnv) {
        for (Long uid : memberIds) {
            if (uid == null || uid <= 0 || uid == fromUserId) {
                continue;
            }
            boolean important = importantTargets != null && importantTargets.contains(uid);
            wsPushService.pushToUser(uid, important ? importantEnv : normalEnv);
        }
    }

    private void fanoutByServer(Map<String, List<Long>> byServer, WsEnvelope env) {
        if (byServer == null || byServer.isEmpty() || env == null) {
            return;
        }
        int batchSize = safePositive(fanoutProps.getBatchSize(), 500);
        String localServerId = routeStore.serverId();

        for (Map.Entry<String, List<Long>> e : byServer.entrySet()) {
            String serverId = e.getKey();
            List<Long> uids = e.getValue();
            if (serverId == null || serverId.isBlank() || uids == null || uids.isEmpty()) {
                continue;
            }
            if (serverId.equals(localServerId)) {
                try (WsWriter.PreparedBytes prepared = wsPushService.prepareBytes(env)) {
                    for (Long uid : uids) {
                        if (uid == null || uid <= 0) {
                            continue;
                        }
                        wsPushService.pushToUserLocalOnly(uid, env, prepared);
                    }
                }
            } else {
                clusterBus.publishPushBatch(serverId, uids, env, batchSize);
            }
        }
    }

    private Routing routeAndGroup(Set<Long> memberIds, long fromUserId, Set<Long> importantTargets) {
        int batchSize = safePositive(fanoutProps.getBatchSize(), 500);

        Map<String, List<Long>> normalByServer = new HashMap<>();
        Map<String, List<Long>> importantByServer = new HashMap<>();
        int online = 0;

        List<Long> batch = new ArrayList<>(batchSize);
        for (Long uid : memberIds) {
            if (uid == null || uid <= 0 || uid == fromUserId) {
                continue;
            }
            batch.add(uid);
            if (batch.size() >= batchSize) {
                BatchOut out = applyBatch(batch, importantTargets, normalByServer, importantByServer);
                if (!out.ok) {
                    return Routing.fail();
                }
                online += out.online;
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            BatchOut out = applyBatch(batch, importantTargets, normalByServer, importantByServer);
            if (!out.ok) {
                return Routing.fail();
            }
            online += out.online;
        }

        return new Routing(true, online, normalByServer, importantByServer);
    }

    private BatchOut applyBatch(List<Long> userIds,
                                Set<Long> importantTargets,
                                Map<String, List<Long>> normalByServer,
                                Map<String, List<Long>> importantByServer) {
        Map<Long, WsRouteStore.RouteInfo> routes = routeStore.batchGet(userIds);
        if (routes == null) {
            return BatchOut.fail();
        }
        if (routes.isEmpty()) {
            return BatchOut.ok(0);
        }
        int online = 0;
        for (Map.Entry<Long, WsRouteStore.RouteInfo> e : routes.entrySet()) {
            Long uid = e.getKey();
            WsRouteStore.RouteInfo info = e.getValue();
            if (uid == null || uid <= 0 || info == null || info.serverId() == null || info.serverId().isBlank()) {
                continue;
            }
            online++;
            boolean important = importantTargets != null && importantTargets.contains(uid);
            Map<String, List<Long>> target = important ? importantByServer : normalByServer;
            target.computeIfAbsent(info.serverId(), k -> new ArrayList<>()).add(uid);
        }
        return BatchOut.ok(online);
    }

    private static int safePositive(int v, int fallback) {
        return v > 0 ? v : fallback;
    }

    private static Mode parseMode(String raw) {
        if (raw == null) {
            return Mode.AUTO;
        }
        return switch (raw.trim().toLowerCase()) {
            case "push", "strategy1" -> Mode.PUSH;
            case "notify", "strategy2" -> Mode.NOTIFY;
            case "none", "off" -> Mode.NONE;
            default -> Mode.AUTO;
        };
    }

    private record Routing(boolean ok, int onlineUserCount, Map<String, List<Long>> normalByServer, Map<String, List<Long>> importantByServer) {
        static Routing fail() {
            return new Routing(false, 0, Map.of(), Map.of());
        }
    }

    private record BatchOut(boolean ok, int online) {
        static BatchOut ok(int online) {
            return new BatchOut(true, online);
        }

        static BatchOut fail() {
            return new BatchOut(false, 0);
        }
    }
}
