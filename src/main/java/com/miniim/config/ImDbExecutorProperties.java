package com.miniim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.executors.db")
public record ImDbExecutorProperties(
        Integer corePoolSize,
        Integer maxPoolSize,
        Integer queueCapacity
) {

    public int corePoolSizeEffective() {
        Integer v = corePoolSize;
        if (v == null) {
            return 8;
        }
        return Math.max(1, v);
    }

    public int maxPoolSizeEffective() {
        Integer v = maxPoolSize;
        if (v == null) {
            return 32;
        }
        return Math.max(1, v);
    }

    public int queueCapacityEffective() {
        Integer v = queueCapacity;
        if (v == null) {
            return 10_000;
        }
        return Math.max(0, v);
    }
}

