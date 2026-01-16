package com.miniim.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.gateway.ws.inbound-queue")
public record WsInboundQueueProperties(
        Boolean enabled,
        Integer maxPendingPerConn
) {

    public boolean enabledEffective() {
        return enabled != null && enabled;
    }

    public int maxPendingPerConnEffective() {
        Integer v = maxPendingPerConn;
        if (v == null) {
            return 2000;
        }
        return Math.max(1, v);
    }
}

