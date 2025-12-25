package com.miniim.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.auth")
public record AuthProperties(
        String issuer,
        String jwtSecret,
        long accessTokenTtlSeconds,
        long refreshTokenTtlSeconds
) {
}
