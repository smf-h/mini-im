package com.miniim.gateway.ws;

import com.miniim.gateway.config.WsGroupUpdatedAtDebounceProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class GroupUpdatedAtDebouncer {

    private final WsGroupUpdatedAtDebounceProperties props;
    private final ConcurrentHashMap<Long, Long> lastWriteAtMsByGroupId = new ConcurrentHashMap<>();
    private final Object clearLock = new Object();

    public GroupUpdatedAtDebouncer(WsGroupUpdatedAtDebounceProperties props) {
        this.props = props;
    }

    public boolean shouldUpdateNow(long groupId, long nowMs) {
        if (groupId <= 0) {
            return true;
        }
        if (props == null || !props.debounceEnabledEffective()) {
            return true;
        }
        int windowMs = props.debounceWindowMsEffective();
        if (windowMs <= 0) {
            return true;
        }

        int maxEntries = props.maxEntriesEffective();
        if (lastWriteAtMsByGroupId.size() > maxEntries) {
            synchronized (clearLock) {
                if (lastWriteAtMsByGroupId.size() > maxEntries) {
                    lastWriteAtMsByGroupId.clear();
                }
            }
        }

        for (; ; ) {
            Long last = lastWriteAtMsByGroupId.get(groupId);
            if (last == null) {
                if (lastWriteAtMsByGroupId.putIfAbsent(groupId, nowMs) == null) {
                    return true;
                }
                continue;
            }
            if (nowMs - last < windowMs) {
                return false;
            }
            if (lastWriteAtMsByGroupId.replace(groupId, last, nowMs)) {
                return true;
            }
        }
    }
}

