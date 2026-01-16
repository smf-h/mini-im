package com.miniim.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.gateway.ws.perf-trace")
public record WsPerfTraceProperties(
        Boolean enabled,
        Double sampleRate,
        Long slowMs
) {

    public boolean enabledEffective() {
        return enabled != null && enabled;
    }

    public double sampleRateEffective() {
        Double v = sampleRate;
        if (v == null) {
            return 0.01;
        }
        if (v <= 0) {
            return 0;
        }
        return Math.min(1.0, v);
    }

    public long slowMsEffective() {
        Long v = slowMs;
        if (v == null) {
            return 500;
        }
        return Math.max(0, v);
    }
}

