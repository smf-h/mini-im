package com.miniim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.executors.ack")
public record ImAckExecutorProperties(
        Integer corePoolSize,
        Integer maxPoolSize,
        Integer queueCapacity
) {

    public static int defaultCorePoolSize() {
        return 4;
    }

    public int corePoolSizeEffective() {
        Integer v = corePoolSize;
        if (v == null || v <= 0) {
            return defaultCorePoolSize();
        }
        return v;
    }

    public int maxPoolSizeEffective() {
        Integer v = maxPoolSize;
        if (v == null || v <= 0) {
            return Math.max(corePoolSizeEffective(), 8);
        }
        return Math.max(v, corePoolSizeEffective());
    }

    public int queueCapacityEffective() {
        Integer v = queueCapacity;
        if (v == null || v <= 0) {
            return 10_000;
        }
        return v;
    }
}

