package com.miniim.gateway.ws.cluster;

import com.miniim.gateway.ws.WsEnvelope;

import java.util.List;

public record WsClusterMessage(
        String type,
        Long userId,
        List<Long> userIds,
        String connId,
        String reason,
        WsEnvelope envelope,
        Long ts
) {

    public static WsClusterMessage kick(long userId, String connId, String reason) {
        return new WsClusterMessage("KICK", userId, null, connId, reason, null, System.currentTimeMillis());
    }

    public static WsClusterMessage push(long userId, WsEnvelope envelope) {
        return new WsClusterMessage("PUSH", userId, null, null, null, envelope, System.currentTimeMillis());
    }

    public static WsClusterMessage pushBatch(List<Long> userIds, WsEnvelope envelope) {
        return new WsClusterMessage("PUSH", null, userIds, null, null, envelope, System.currentTimeMillis());
    }
}
