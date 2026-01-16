package com.miniim.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.gateway.ws.encode")
public record WsEncodeProperties(
        Boolean enabled,
        Boolean encodeOnExecutor,
        Boolean useBytes
) {

    public boolean enabledEffective() {
        return enabled != null && enabled;
    }

    public boolean encodeOnExecutorEffective() {
        return encodeOnExecutor == null || encodeOnExecutor;
    }

    public boolean useBytesEffective() {
        return useBytes == null || useBytes;
    }
}
