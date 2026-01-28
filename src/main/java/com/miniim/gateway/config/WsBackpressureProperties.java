package com.miniim.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.gateway.ws.backpressure")
public record WsBackpressureProperties(
        Boolean enabled,
        Integer writeBufferLowWaterMarkBytes,
        Integer writeBufferHighWaterMarkBytes,
        Long closeUnwritableAfterMs,
        Boolean dropWhenUnwritable
) {

    public boolean enabledEffective() {
        return enabled == null || enabled;
    }

    public int lowWaterMarkBytesEffective() {
        Integer v = writeBufferLowWaterMarkBytes;
        if (v == null || v <= 0) {
            return 256 * 1024;
        }
        return v;
    }

    public int highWaterMarkBytesEffective() {
        Integer v = writeBufferHighWaterMarkBytes;
        if (v == null || v <= 0) {
            return 512 * 1024;
        }
        int low = lowWaterMarkBytesEffective();
        return Math.max(v, low + 1);
    }

    public long closeUnwritableAfterMsEffective() {
        Long v = closeUnwritableAfterMs;
        if (v == null) {
            return 3_000;
        }
        return v;
    }

    public boolean dropWhenUnwritableEffective() {
        return dropWhenUnwritable == null || dropWhenUnwritable;
    }
}

