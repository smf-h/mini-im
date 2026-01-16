package com.miniim.gateway.config;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.gateway.ws")
public record GatewayProperties(
        String host,
        int port,
        String path,
        String instanceId,
        Integer bossThreads,
        Integer workerThreads
) {

    private static final String RUNTIME_INSTANCE_SUFFIX = UUID.randomUUID()
            .toString()
            .replace("-", "")
            .substring(0, 8);

    public String effectiveInstanceId() {
        if (instanceId != null && !instanceId.isBlank()) {
            return instanceId.trim();
        }
        String h = host == null ? "" : host.trim();
        if (h.isEmpty()) {
            h = "unknown";
        }
        return h + ":" + port + ":" + RUNTIME_INSTANCE_SUFFIX;
    }

    public int bossThreadsEffective() {
        Integer v = bossThreads;
        if (v == null || v <= 0) {
            return 1;
        }
        return v;
    }

    public Integer workerThreadsEffectiveOrNull() {
        Integer v = workerThreads;
        if (v == null || v <= 0) {
            return null;
        }
        return v;
    }
}
