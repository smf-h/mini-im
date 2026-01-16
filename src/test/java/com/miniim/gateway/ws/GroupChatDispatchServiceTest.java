package com.miniim.gateway.ws;

import com.miniim.gateway.config.GroupChatStrategyProperties;
import com.miniim.gateway.config.GroupFanoutProperties;
import com.miniim.gateway.session.WsRouteStore;
import com.miniim.gateway.ws.cluster.WsClusterBus;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupChatDispatchServiceTest {

    @Test
    void autoShouldChooseNotifyWhenOnlineTooMany() {
        WsRouteStore routeStore = mock(WsRouteStore.class);
        WsClusterBus clusterBus = mock(WsClusterBus.class);
        WsPushService pushService = mock(WsPushService.class);

        GroupFanoutProperties fanoutProps = new GroupFanoutProperties();
        fanoutProps.setEnabled(true);
        fanoutProps.setBatchSize(500);
        fanoutProps.setFailOpen(true);

        GroupChatStrategyProperties strategyProps = new GroupChatStrategyProperties();
        strategyProps.setMode("auto");
        strategyProps.setGroupSizeThreshold(2000);
        strategyProps.setOnlineUserThreshold(2);
        strategyProps.setNotifyMaxOnlineUser(2000);
        strategyProps.setHugeGroupNoNotifySize(10000);

        when(routeStore.serverId()).thenReturn("local");
        when(routeStore.batchGet(anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<Long> ids = inv.getArgument(0, List.class);
            Map<Long, WsRouteStore.RouteInfo> out = new HashMap<>();
            for (Long id : ids) {
                out.put(id, new WsRouteStore.RouteInfo("remote", null, "remote|x"));
            }
            return out;
        });

        GroupChatDispatchService svc = new GroupChatDispatchService(routeStore, clusterBus, pushService, fanoutProps, strategyProps, null);

        Set<Long> members = Set.of(1L, 2L, 3L);
        WsEnvelope normalMsg = new WsEnvelope();
        normalMsg.type = "GROUP_CHAT";
        WsEnvelope importantMsg = new WsEnvelope();
        importantMsg.type = "GROUP_CHAT";
        importantMsg.important = true;

        WsEnvelope normalNotify = new WsEnvelope();
        normalNotify.type = "GROUP_NOTIFY";
        WsEnvelope importantNotify = new WsEnvelope();
        importantNotify.type = "GROUP_NOTIFY";
        importantNotify.important = true;

        svc.dispatch(members, 1L, Set.of(), normalMsg, importantMsg, normalNotify, importantNotify);

        verify(clusterBus, times(1)).publishPushBatch(eq("remote"), any(), eq(normalNotify), anyInt());
        verify(clusterBus, never()).publishPushBatch(eq("remote"), any(), eq(normalMsg), anyInt());
    }

    @Test
    void hugeGroupAutoShouldSkipNotifyAndRouting() {
        WsRouteStore routeStore = mock(WsRouteStore.class);
        WsClusterBus clusterBus = mock(WsClusterBus.class);
        WsPushService pushService = mock(WsPushService.class);

        GroupFanoutProperties fanoutProps = new GroupFanoutProperties();
        fanoutProps.setEnabled(true);
        fanoutProps.setBatchSize(500);
        fanoutProps.setFailOpen(true);

        GroupChatStrategyProperties strategyProps = new GroupChatStrategyProperties();
        strategyProps.setMode("auto");
        strategyProps.setHugeGroupNoNotifySize(2);

        GroupChatDispatchService svc = new GroupChatDispatchService(routeStore, clusterBus, pushService, fanoutProps, strategyProps, null);

        Set<Long> members = new HashSet<>();
        members.add(1L);
        members.add(2L);
        members.add(3L);

        WsEnvelope normalMsg = new WsEnvelope();
        normalMsg.type = "GROUP_CHAT";
        WsEnvelope importantMsg = new WsEnvelope();
        importantMsg.type = "GROUP_CHAT";
        importantMsg.important = true;

        WsEnvelope normalNotify = new WsEnvelope();
        normalNotify.type = "GROUP_NOTIFY";
        WsEnvelope importantNotify = new WsEnvelope();
        importantNotify.type = "GROUP_NOTIFY";
        importantNotify.important = true;

        svc.dispatch(members, 1L, Set.of(), normalMsg, importantMsg, normalNotify, importantNotify);

        verify(routeStore, never()).batchGet(anyList());
        verify(clusterBus, never()).publishPushBatch(any(), any(), any(), anyInt());
        verify(pushService, never()).pushToUser(anyLong(), any());
    }

    @Test
    void redisFailureShouldFailOpenToLegacyPush() {
        WsRouteStore routeStore = mock(WsRouteStore.class);
        WsClusterBus clusterBus = mock(WsClusterBus.class);
        WsPushService pushService = mock(WsPushService.class);

        GroupFanoutProperties fanoutProps = new GroupFanoutProperties();
        fanoutProps.setEnabled(true);
        fanoutProps.setBatchSize(500);
        fanoutProps.setFailOpen(true);

        GroupChatStrategyProperties strategyProps = new GroupChatStrategyProperties();
        strategyProps.setMode("push");

        when(routeStore.serverId()).thenReturn("local");
        when(routeStore.batchGet(anyList())).thenReturn(null);

        GroupChatDispatchService svc = new GroupChatDispatchService(routeStore, clusterBus, pushService, fanoutProps, strategyProps, null);

        WsEnvelope normalMsg = new WsEnvelope();
        normalMsg.type = "GROUP_CHAT";
        WsEnvelope importantMsg = new WsEnvelope();
        importantMsg.type = "GROUP_CHAT";
        importantMsg.important = true;

        svc.dispatch(Set.of(1L, 2L), 1L, Set.of(), normalMsg, importantMsg, new WsEnvelope(), new WsEnvelope());

        verify(pushService, times(1)).pushToUser(eq(2L), eq(normalMsg));
        verify(clusterBus, never()).publishPushBatch(any(), any(), any(), anyInt());
    }
}
