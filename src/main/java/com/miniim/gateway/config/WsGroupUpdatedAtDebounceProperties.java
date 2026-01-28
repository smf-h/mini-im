package com.miniim.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.gateway.ws.group.updated-at")
public record WsGroupUpdatedAtDebounceProperties(
        Boolean debounceEnabled,
        Integer debounceWindowMs,
        Integer maxEntries,
        Boolean syncUpdate,
        String mode
) {

    public boolean debounceEnabledEffective() {
        return debounceEnabled == null || debounceEnabled;
    }

    public int debounceWindowMsEffective() {
        Integer v = debounceWindowMs;
        if (v == null) {
            return 1000;
        }
        return Math.max(0, v);
    }

    public int maxEntriesEffective() {
        Integer v = maxEntries;
        if (v == null) {
            return 200_000;
        }
        return Math.max(1, v);
    }

    public boolean syncUpdateEffective() {
        return syncUpdate != null && syncUpdate;
    }

    public String modeEffective() {
        return mode == null ? "post_db" : mode.trim().toLowerCase();
    }

    public boolean inlineDbEffective() {
        return "inline_db".equals(modeEffective()) || "inline".equals(modeEffective());
    }
}
