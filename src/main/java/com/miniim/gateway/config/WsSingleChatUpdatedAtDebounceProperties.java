package com.miniim.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.gateway.ws.single-chat.updated-at")
public record WsSingleChatUpdatedAtDebounceProperties(
        Boolean debounceEnabled,
        Integer debounceWindowMs,
        Integer maxEntries,
        Boolean syncUpdate
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
}
