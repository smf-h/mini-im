package com.miniim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.executors.ws-encode")
public record ImWsEncodeExecutorProperties(
        Integer corePoolSize,
        Integer maxPoolSize,
        Integer queueCapacity
) {

    public static int defaultCorePoolSize() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(1, cores);
    }

    public int corePoolSizeEffective() {
        Integer v = corePoolSize;
        if (v == null) {
            return defaultCorePoolSize();
        }
        return Math.max(1, v);
    }

    public int maxPoolSizeEffective() {
        Integer v = maxPoolSize;
        if (v == null) {
            return corePoolSizeEffective();
        }
        return Math.max(1, v);
    }

    public int queueCapacityEffective() {
        Integer v = queueCapacity;
        if (v == null) {
            return 5_000;
        }
        return Math.max(0, v);
    }
}

