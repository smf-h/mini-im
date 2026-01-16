package com.miniim.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.gateway.ws.single-chat.two-phase")
public record WsSingleChatTwoPhaseProperties(
        Boolean enabled,
        Boolean deliverBeforeSaved,
        Boolean failOpen,
        String mode,

        String acceptedStreamKey,
        String toSaveStreamKey,
        String deliverGroup,
        String saveGroup,

        Integer batchSize,
        Long blockMs,
        Long leaderLockTtlMs,
        Integer localQueueCapacity
) {

    public boolean enabledEffective() {
        return enabled != null && enabled;
    }

    public boolean deliverBeforeSavedEffective() {
        return deliverBeforeSaved != null && deliverBeforeSaved;
    }

    public boolean failOpenEffective() {
        return failOpen == null || failOpen;
    }

    public String modeEffective() {
        String v = mode;
        if (v == null || v.isBlank()) {
            return "redis";
        }
        String t = v.trim().toLowerCase(java.util.Locale.ROOT);
        return ("local".equals(t) || "redis".equals(t)) ? t : "redis";
    }

    public String acceptedStreamKeyEffective() {
        String v = acceptedStreamKey;
        if (v == null || v.isBlank()) {
            return "im:stream:single_chat:accepted";
        }
        return v.trim();
    }

    public String toSaveStreamKeyEffective() {
        String v = toSaveStreamKey;
        if (v == null || v.isBlank()) {
            return "im:stream:single_chat:to_save";
        }
        return v.trim();
    }

    public String deliverGroupEffective() {
        String v = deliverGroup;
        if (v == null || v.isBlank()) {
            return "im:cg:single_chat:deliver";
        }
        return v.trim();
    }

    public String saveGroupEffective() {
        String v = saveGroup;
        if (v == null || v.isBlank()) {
            return "im:cg:single_chat:save";
        }
        return v.trim();
    }

    public int batchSizeEffective() {
        Integer v = batchSize;
        if (v == null) {
            return 50;
        }
        return Math.max(1, v);
    }

    public long blockMsEffective() {
        Long v = blockMs;
        if (v == null) {
            return 200;
        }
        return Math.max(0, v);
    }

    public long leaderLockTtlMsEffective() {
        Long v = leaderLockTtlMs;
        if (v == null) {
            return 2_000;
        }
        return Math.max(200, v);
    }

    public int localQueueCapacityEffective() {
        Integer v = localQueueCapacity;
        if (v == null) {
            return 200_000;
        }
        return Math.max(1000, v);
    }
}
