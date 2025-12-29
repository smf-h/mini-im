package com.miniim.gateway.session;

import com.miniim.domain.enums.CallStatus;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Component
public class CallRegistry {

    @Getter
    public static class CallSession {
        private final long callId;
        private final long callerUserId;
        private final long calleeUserId;
        private final long createdAtMs;

        private volatile CallStatus status = CallStatus.RINGING;
        private volatile Long acceptedAtMs;
        private volatile ScheduledFuture<?> timeoutFuture;

        public CallSession(long callId, long callerUserId, long calleeUserId) {
            this.callId = callId;
            this.callerUserId = callerUserId;
            this.calleeUserId = calleeUserId;
            this.createdAtMs = Instant.now().toEpochMilli();
        }

        public long peerOf(long userId) {
            return userId == callerUserId ? calleeUserId : callerUserId;
        }

        public boolean isParticipant(long userId) {
            return userId == callerUserId || userId == calleeUserId;
        }

        public void setStatus(CallStatus status) {
            this.status = status;
        }

        public CallStatus getStatus() {
            return status;
        }

        public Long getAcceptedAtMs() {
            return acceptedAtMs;
        }

        public void markAccepted() {
            this.acceptedAtMs = Instant.now().toEpochMilli();
        }

        public ScheduledFuture<?> getTimeoutFuture() {
            return timeoutFuture;
        }

        public void setTimeoutFuture(ScheduledFuture<?> timeoutFuture) {
            this.timeoutFuture = timeoutFuture;
        }
    }

    private final Object lock = new Object();

    private final ConcurrentHashMap<Long, CallSession> callsById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> activeCallIdByUser = new ConcurrentHashMap<>();

    public CallSession get(long callId) {
        return callsById.get(callId);
    }

    public Long getActiveCallId(long userId) {
        return activeCallIdByUser.get(userId);
    }

    public boolean isBusy(long userId) {
        return activeCallIdByUser.containsKey(userId);
    }

    public CallSession tryCreate(long callId, long callerUserId, long calleeUserId) {
        synchronized (lock) {
            if (activeCallIdByUser.containsKey(callerUserId) || activeCallIdByUser.containsKey(calleeUserId)) {
                return null;
            }
            CallSession session = new CallSession(callId, callerUserId, calleeUserId);
            callsById.put(callId, session);
            activeCallIdByUser.put(callerUserId, callId);
            activeCallIdByUser.put(calleeUserId, callId);
            return session;
        }
    }

    public void clear(long callId) {
        CallSession session = callsById.remove(callId);
        if (session == null) return;
        synchronized (lock) {
            activeCallIdByUser.remove(session.getCallerUserId(), callId);
            activeCallIdByUser.remove(session.getCalleeUserId(), callId);
        }
        ScheduledFuture<?> f = session.getTimeoutFuture();
        if (f != null) {
            f.cancel(false);
        }
    }

    public CallSession clearByUser(long userId) {
        Long callId = activeCallIdByUser.get(userId);
        if (callId == null) return null;
        CallSession session = callsById.get(callId);
        clear(callId);
        return session;
    }
}
