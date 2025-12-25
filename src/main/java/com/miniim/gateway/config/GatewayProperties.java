package com.miniim.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.gateway.ws")
public record GatewayProperties(
        String host,
        int port,
        String path
) {
}
