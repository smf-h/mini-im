package com.miniim.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.gateway.ws.resend")
public record WsResendProperties(
        Boolean afterAuthEnabled
) {

    public boolean afterAuthEnabledEffective() {
        return afterAuthEnabled == null || afterAuthEnabled;
    }
}

