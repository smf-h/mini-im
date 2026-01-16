package com.miniim.gateway.ws;

import com.miniim.gateway.config.WsSingleChatUpdatedAtDebounceProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class SingleChatUpdatedAtDebouncer {

    private final WsSingleChatUpdatedAtDebounceProperties props;
    private final ConcurrentHashMap<Long, Long> lastWriteAtMsByChatId = new ConcurrentHashMap<>();
    private final Object clearLock = new Object();

    public SingleChatUpdatedAtDebouncer(WsSingleChatUpdatedAtDebounceProperties props) {
        this.props = props;
    }

    public boolean shouldUpdateNow(long singleChatId, long nowMs) {
        if (singleChatId <= 0) {
            return true;
        }
        if (props == null || !props.debounceEnabledEffective()) {
            return true;
        }
        int windowMs = props.debounceWindowMsEffective();
        if (windowMs <= 0) {
            return true;
        }

        // Cap memory in worst-case (e.g., load test with huge userBase / many chats).
        int maxEntries = props.maxEntriesEffective();
        if (lastWriteAtMsByChatId.size() > maxEntries) {
            synchronized (clearLock) {
                if (lastWriteAtMsByChatId.size() > maxEntries) {
                    lastWriteAtMsByChatId.clear();
                }
            }
        }

        for (; ; ) {
            Long last = lastWriteAtMsByChatId.get(singleChatId);
            if (last == null) {
                if (lastWriteAtMsByChatId.putIfAbsent(singleChatId, nowMs) == null) {
                    return true;
                }
                continue;
            }
            if (nowMs - last < windowMs) {
                return false;
            }
            if (lastWriteAtMsByChatId.replace(singleChatId, last, nowMs)) {
                return true;
            }
        }
    }
}

