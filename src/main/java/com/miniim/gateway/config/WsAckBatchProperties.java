package com.miniim.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.gateway.ws.ack")
public record WsAckBatchProperties(
        Boolean batchEnabled,
        Integer batchWindowMs
) {

    public boolean batchEnabledEffective() {
        return batchEnabled == null || batchEnabled;
    }

    public int batchWindowMsEffective() {
        Integer v = batchWindowMs;
        if (v == null || v <= 0) {
            return 1000;
        }
        return v;
    }
}

