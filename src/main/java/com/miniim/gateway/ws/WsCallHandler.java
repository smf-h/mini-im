package com.miniim.gateway.ws;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.miniim.domain.entity.CallRecordEntity;
import com.miniim.domain.entity.FriendRelationEntity;
import com.miniim.domain.enums.CallStatus;
import com.miniim.domain.service.CallRecordService;
import com.miniim.domain.service.FriendRelationService;
import com.miniim.gateway.session.CallRegistry;
import com.miniim.gateway.session.SessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 单聊 WebRTC（Phase1）信令处理（WS `CALL_*`）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>校验/门禁（必须是好友、不能自呼、必须在线等）</li>
 *   <li>CallRegistry 并发占用与超时</li>
 *   <li>CallRecord 落库与状态推进</li>
 *   <li>向 peer best-effort 转发信令（不落日志、不做媒体中转）</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WsCallHandler {

    private static final int MAX_SDP_LEN = 120_000;
    private static final int MAX_ICE_LEN = 4096;
    private static final int CALL_RING_TIMEOUT_SEC = 30;

    private final SessionRegistry sessionRegistry;
    private final CallRegistry callRegistry;
    private final CallRecordService callRecordService;
    private final FriendRelationService friendRelationService;
    private final WsWriter wsWriter;
    @Qualifier("imDbExecutor")
    private final Executor dbExecutor;

    public void handleInvite(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel channel = ctx.channel();
        Long fromUserId = channel.attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId == null) {
            writeCallError(ctx, null, "unauthorized", null, msg.getClientMsgId());
            return;
        }
        if (!validateCallInvite(ctx, msg)) {
            return;
        }

        long callerUserId = fromUserId;
        long calleeUserId = msg.getTo();
        if (callerUserId == calleeUserId) {
            writeCallError(ctx, null, "cannot_call_self", null, msg.getClientMsgId());
            return;
        }

        boolean friend = friendRelationService.count(new LambdaQueryWrapper<FriendRelationEntity>()
                .nested(w -> w.eq(FriendRelationEntity::getUser1Id, callerUserId).eq(FriendRelationEntity::getUser2Id, calleeUserId)
                        .or()
                        .eq(FriendRelationEntity::getUser1Id, calleeUserId).eq(FriendRelationEntity::getUser2Id, callerUserId))) > 0;
        if (!friend) {
            writeCallError(ctx, null, "not_friend", null, msg.getClientMsgId());
            return;
        }

        if (callRegistry.isBusy(callerUserId) || callRegistry.isBusy(calleeUserId)) {
            long callId = IdWorker.getId();
            persistCallFailed(callId, callerUserId, calleeUserId, "busy");
            writeCallError(ctx, callId, "busy", "busy", msg.getClientMsgId());
            return;
        }

        List<Channel> calleeChannels = sessionRegistry.getChannels(calleeUserId);
        boolean calleeOnline = calleeChannels != null && calleeChannels.stream().anyMatch(ch -> ch != null && ch.isActive());
        if (!calleeOnline) {
            long callId = IdWorker.getId();
            persistCallFailed(callId, callerUserId, calleeUserId, "offline");
            writeCallError(ctx, callId, "callee_offline", "offline", msg.getClientMsgId());
            return;
        }

        long callId = IdWorker.getId();
        CallRegistry.CallSession session = callRegistry.tryCreate(callId, callerUserId, calleeUserId);
        if (session == null) {
            persistCallFailed(callId, callerUserId, calleeUserId, "busy");
            writeCallError(ctx, callId, "busy", "busy", msg.getClientMsgId());
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        CompletableFuture<Boolean> saveFuture = CompletableFuture
                .supplyAsync(() -> {
                    CallRecordEntity record = new CallRecordEntity();
                    record.setCallId(callId);
                    record.setCallerUserId(callerUserId);
                    record.setCalleeUserId(calleeUserId);
                    record.setStatus(CallStatus.RINGING);
                    record.setFailReason(null);
                    record.setStartedAt(now);
                    return callRecordService.save(record);
                }, dbExecutor)
                .orTimeout(3, TimeUnit.SECONDS);

        saveFuture.whenComplete((ok, error) -> {
            if (error != null || ok == null || !ok) {
                callRegistry.clear(callId);
                ctx.executor().execute(() -> writeCallError(ctx, callId, "internal_error", null, msg.getClientMsgId()));
                return;
            }

            session.setTimeoutFuture(ctx.executor().schedule(() -> handleTimeout(callId), CALL_RING_TIMEOUT_SEC, TimeUnit.SECONDS));

            ctx.executor().execute(() -> {
                WsEnvelope ack = new WsEnvelope();
                ack.setType("CALL_INVITE_OK");
                ack.setFrom(callerUserId);
                ack.setTo(calleeUserId);
                ack.setCallId(callId);
                ack.setCallKind(msg.getCallKind());
                ack.setClientMsgId(msg.getClientMsgId());
                ack.setTs(Instant.now().toEpochMilli());
                wsWriter.write(ctx, ack);
            });

            WsEnvelope incoming = new WsEnvelope();
            incoming.setType("CALL_INVITE");
            incoming.setFrom(callerUserId);
            incoming.setTo(calleeUserId);
            incoming.setCallId(callId);
            incoming.setCallKind(msg.getCallKind());
            incoming.setSdp(msg.getSdp());
            incoming.setTs(Instant.now().toEpochMilli());
            forwardToUser(calleeUserId, incoming);
        });
    }

    public void handleAccept(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel channel = ctx.channel();
        Long fromUserId = channel.attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId == null) {
            writeCallError(ctx, msg.getCallId(), "unauthorized", null, msg.getClientMsgId());
            return;
        }
        if (!validateCallAccept(ctx, msg)) {
            return;
        }

        Long callId = msg.getCallId();
        if (callId == null) {
            writeCallError(ctx, null, "missing_call_id", null, msg.getClientMsgId());
            return;
        }
        CallRegistry.CallSession session = callRegistry.get(callId);
        if (session == null) {
            writeCallError(ctx, callId, "call_not_found", null, msg.getClientMsgId());
            return;
        }
        long userId = fromUserId;
        if (!session.isParticipant(userId)) {
            writeCallError(ctx, callId, "call_not_participant", null, msg.getClientMsgId());
            return;
        }
        if (userId != session.getCalleeUserId()) {
            writeCallError(ctx, callId, "only_callee_can_accept", null, msg.getClientMsgId());
            return;
        }
        if (session.getStatus() != CallStatus.RINGING) {
            writeCallError(ctx, callId, "call_not_ringing", null, msg.getClientMsgId());
            return;
        }

        session.setStatus(CallStatus.ACCEPTED);
        session.markAccepted();
        LocalDateTime now = LocalDateTime.now();
        CompletableFuture<Boolean> updateFuture = CompletableFuture
                .supplyAsync(() -> callRecordService.update(new LambdaUpdateWrapper<CallRecordEntity>()
                        .eq(CallRecordEntity::getCallId, callId)
                        .set(CallRecordEntity::getStatus, CallStatus.ACCEPTED)
                        .set(CallRecordEntity::getAcceptedAt, now)), dbExecutor)
                .orTimeout(3, TimeUnit.SECONDS);

        updateFuture.whenComplete((ok, error) -> {
            if (error != null || ok == null || !ok) {
                ctx.executor().execute(() -> writeCallError(ctx, callId, "internal_error", null, msg.getClientMsgId()));
                return;
            }

            long peer = session.peerOf(userId);
            WsEnvelope out = new WsEnvelope();
            out.setType("CALL_ACCEPT");
            out.setFrom(userId);
            out.setTo(peer);
            out.setCallId(callId);
            out.setCallKind(msg.getCallKind());
            out.setSdp(msg.getSdp());
            out.setTs(Instant.now().toEpochMilli());
            forwardToUser(peer, out);
        });
    }

    public void handleReject(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel channel = ctx.channel();
        Long fromUserId = channel.attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId == null) {
            writeCallError(ctx, msg.getCallId(), "unauthorized", null, msg.getClientMsgId());
            return;
        }
        if (!validateCallBasic(ctx, msg)) {
            return;
        }

        Long callId = msg.getCallId();
        if (callId == null) {
            writeCallError(ctx, null, "missing_call_id", null, msg.getClientMsgId());
            return;
        }
        CallRegistry.CallSession session = callRegistry.get(callId);
        if (session == null) {
            writeCallError(ctx, callId, "call_not_found", null, msg.getClientMsgId());
            return;
        }
        long userId = fromUserId;
        if (userId != session.getCalleeUserId()) {
            writeCallError(ctx, callId, "only_callee_can_reject", null, msg.getClientMsgId());
            return;
        }
        if (session.getStatus() != CallStatus.RINGING) {
            writeCallError(ctx, callId, "call_not_ringing", null, msg.getClientMsgId());
            return;
        }

        session.setStatus(CallStatus.REJECTED);
        LocalDateTime now = LocalDateTime.now();
        CompletableFuture<Boolean> updateFuture = CompletableFuture
                .supplyAsync(() -> callRecordService.update(new LambdaUpdateWrapper<CallRecordEntity>()
                        .eq(CallRecordEntity::getCallId, callId)
                        .set(CallRecordEntity::getStatus, CallStatus.REJECTED)
                        .set(CallRecordEntity::getFailReason, safeCallReason(msg.getCallReason()))
                        .set(CallRecordEntity::getEndedAt, now)), dbExecutor)
                .orTimeout(3, TimeUnit.SECONDS);

        updateFuture.whenComplete((ok, error) -> {
            callRegistry.clear(callId);
            if (error != null || ok == null || !ok) {
                ctx.executor().execute(() -> writeCallError(ctx, callId, "internal_error", null, msg.getClientMsgId()));
                return;
            }
            long peer = session.peerOf(userId);
            WsEnvelope out = new WsEnvelope();
            out.setType("CALL_REJECT");
            out.setFrom(userId);
            out.setTo(peer);
            out.setCallId(callId);
            out.setCallReason(safeCallReason(msg.getCallReason()));
            out.setTs(Instant.now().toEpochMilli());
            forwardToUser(peer, out);
        });
    }

    public void handleCancel(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel channel = ctx.channel();
        Long fromUserId = channel.attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId == null) {
            writeCallError(ctx, msg.getCallId(), "unauthorized", null, msg.getClientMsgId());
            return;
        }
        if (!validateCallBasic(ctx, msg)) {
            return;
        }

        Long callId = msg.getCallId();
        if (callId == null) {
            writeCallError(ctx, null, "missing_call_id", null, msg.getClientMsgId());
            return;
        }
        CallRegistry.CallSession session = callRegistry.get(callId);
        if (session == null) {
            writeCallError(ctx, callId, "call_not_found", null, msg.getClientMsgId());
            return;
        }
        long userId = fromUserId;
        if (userId != session.getCallerUserId()) {
            writeCallError(ctx, callId, "only_caller_can_cancel", null, msg.getClientMsgId());
            return;
        }
        if (session.getStatus() != CallStatus.RINGING) {
            writeCallError(ctx, callId, "call_not_ringing", null, msg.getClientMsgId());
            return;
        }

        session.setStatus(CallStatus.CANCELED);
        LocalDateTime now = LocalDateTime.now();
        CompletableFuture<Boolean> updateFuture = CompletableFuture
                .supplyAsync(() -> callRecordService.update(new LambdaUpdateWrapper<CallRecordEntity>()
                        .eq(CallRecordEntity::getCallId, callId)
                        .set(CallRecordEntity::getStatus, CallStatus.CANCELED)
                        .set(CallRecordEntity::getFailReason, safeCallReason(msg.getCallReason()))
                        .set(CallRecordEntity::getEndedAt, now)), dbExecutor)
                .orTimeout(3, TimeUnit.SECONDS);

        updateFuture.whenComplete((ok, error) -> {
            callRegistry.clear(callId);
            if (error != null || ok == null || !ok) {
                ctx.executor().execute(() -> writeCallError(ctx, callId, "internal_error", null, msg.getClientMsgId()));
                return;
            }
            long peer = session.peerOf(userId);
            WsEnvelope out = new WsEnvelope();
            out.setType("CALL_CANCEL");
            out.setFrom(userId);
            out.setTo(peer);
            out.setCallId(callId);
            out.setCallReason(safeCallReason(msg.getCallReason()));
            out.setTs(Instant.now().toEpochMilli());
            forwardToUser(peer, out);
        });
    }

    public void handleEnd(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel channel = ctx.channel();
        Long fromUserId = channel.attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId == null) {
            writeCallError(ctx, msg.getCallId(), "unauthorized", null, msg.getClientMsgId());
            return;
        }
        if (!validateCallBasic(ctx, msg)) {
            return;
        }

        Long callId = msg.getCallId();
        if (callId == null) {
            writeCallError(ctx, null, "missing_call_id", null, msg.getClientMsgId());
            return;
        }
        CallRegistry.CallSession session = callRegistry.get(callId);
        if (session == null) {
            writeCallError(ctx, callId, "call_not_found", null, msg.getClientMsgId());
            return;
        }
        long userId = fromUserId;
        if (!session.isParticipant(userId)) {
            writeCallError(ctx, callId, "call_not_participant", null, msg.getClientMsgId());
            return;
        }

        CallStatus nextStatus = session.getStatus() == CallStatus.ACCEPTED
                ? CallStatus.ENDED
                : (userId == session.getCallerUserId() ? CallStatus.CANCELED : CallStatus.REJECTED);
        LocalDateTime now = LocalDateTime.now();
        Integer durationSeconds = null;
        if (nextStatus == CallStatus.ENDED) {
            Long acceptedAtMs = session.getAcceptedAtMs();
            if (acceptedAtMs != null) {
                long d = Math.max(0, (Instant.now().toEpochMilli() - acceptedAtMs) / 1000);
                durationSeconds = (int) Math.min(d, Integer.MAX_VALUE);
            }
        }
        final Integer durationSecondsFinal = durationSeconds;

        CompletableFuture<Boolean> updateFuture = CompletableFuture
                .supplyAsync(() -> {
                    LambdaUpdateWrapper<CallRecordEntity> w = new LambdaUpdateWrapper<CallRecordEntity>()
                            .eq(CallRecordEntity::getCallId, callId)
                            .set(CallRecordEntity::getStatus, nextStatus)
                            .set(CallRecordEntity::getFailReason, safeCallReason(msg.getCallReason()))
                            .set(CallRecordEntity::getEndedAt, now);
                    if (durationSecondsFinal != null) {
                        w.set(CallRecordEntity::getDurationSeconds, durationSecondsFinal);
                    }
                    return callRecordService.update(w);
                }, dbExecutor)
                .orTimeout(3, TimeUnit.SECONDS);

        updateFuture.whenComplete((ok, error) -> {
            callRegistry.clear(callId);
            if (error != null || ok == null || !ok) {
                ctx.executor().execute(() -> writeCallError(ctx, callId, "internal_error", null, msg.getClientMsgId()));
                return;
            }
            long peer = session.peerOf(userId);
            WsEnvelope out = new WsEnvelope();
            out.setType("CALL_END");
            out.setFrom(userId);
            out.setTo(peer);
            out.setCallId(callId);
            out.setCallReason(safeCallReason(msg.getCallReason()));
            out.setTs(Instant.now().toEpochMilli());
            forwardToUser(peer, out);
        });
    }

    public void handleIce(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel channel = ctx.channel();
        Long fromUserId = channel.attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId == null) {
            writeCallError(ctx, msg.getCallId(), "unauthorized", null, msg.getClientMsgId());
            return;
        }
        if (!validateCallIce(ctx, msg)) {
            return;
        }

        Long callId = msg.getCallId();
        CallRegistry.CallSession session = callRegistry.get(callId);
        if (session == null) {
            writeCallError(ctx, callId, "call_not_found", null, msg.getClientMsgId());
            return;
        }
        long userId = fromUserId;
        if (!session.isParticipant(userId)) {
            writeCallError(ctx, callId, "call_not_participant", null, msg.getClientMsgId());
            return;
        }

        long peer = session.peerOf(userId);
        WsEnvelope out = new WsEnvelope();
        out.setType("CALL_ICE");
        out.setFrom(userId);
        out.setTo(peer);
        out.setCallId(callId);
        out.setIceCandidate(msg.getIceCandidate());
        out.setIceSdpMid(msg.getIceSdpMid());
        out.setIceSdpMLineIndex(msg.getIceSdpMLineIndex());
        out.setTs(Instant.now().toEpochMilli());
        forwardToUser(peer, out);
    }

    public void onChannelInactive(Long userId) {
        if (userId == null) {
            return;
        }
        CallRegistry.CallSession session = callRegistry.clearByUser(userId);
        if (session == null) {
            return;
        }

        long peer = session.peerOf(userId);
        long callId = session.getCallId();
        LocalDateTime now = LocalDateTime.now();
        Integer durationSeconds = null;
        if (session.getAcceptedAtMs() != null) {
            long d = Math.max(0, (Instant.now().toEpochMilli() - session.getAcceptedAtMs()) / 1000);
            durationSeconds = (int) Math.min(d, Integer.MAX_VALUE);
        }
        final Integer durationSecondsFinal = durationSeconds;

        CompletableFuture.runAsync(() -> {
            LambdaUpdateWrapper<CallRecordEntity> w = new LambdaUpdateWrapper<CallRecordEntity>()
                    .eq(CallRecordEntity::getCallId, callId)
                    .set(CallRecordEntity::getStatus, CallStatus.FAILED)
                    .set(CallRecordEntity::getFailReason, "peer_disconnect")
                    .set(CallRecordEntity::getEndedAt, now);
            if (durationSecondsFinal != null) {
                w.set(CallRecordEntity::getDurationSeconds, durationSecondsFinal);
            }
            callRecordService.update(w);
        }, dbExecutor);

        WsEnvelope out = new WsEnvelope();
        out.setType("CALL_END");
        out.setFrom(userId);
        out.setTo(peer);
        out.setCallId(callId);
        out.setCallReason("peer_disconnect");
        out.setTs(Instant.now().toEpochMilli());
        forwardToUser(peer, out);
    }

    private void handleTimeout(long callId) {
        CallRegistry.CallSession session = callRegistry.get(callId);
        if (session == null) {
            return;
        }
        if (session.getStatus() != CallStatus.RINGING) {
            return;
        }
        session.setStatus(CallStatus.MISSED);
        LocalDateTime now = LocalDateTime.now();
        CompletableFuture<Boolean> updateFuture = CompletableFuture
                .supplyAsync(() -> callRecordService.update(new LambdaUpdateWrapper<CallRecordEntity>()
                        .eq(CallRecordEntity::getCallId, callId)
                        .set(CallRecordEntity::getStatus, CallStatus.MISSED)
                        .set(CallRecordEntity::getFailReason, "timeout")
                        .set(CallRecordEntity::getEndedAt, now)), dbExecutor);
        updateFuture.whenComplete((ok, error) -> callRegistry.clear(callId));

        WsEnvelope out = new WsEnvelope();
        out.setType("CALL_TIMEOUT");
        out.setCallId(callId);
        out.setCallReason("timeout");
        out.setTs(Instant.now().toEpochMilli());
        forwardToUser(session.getCallerUserId(), out);
        forwardToUser(session.getCalleeUserId(), out);
    }

    private void forwardToUser(long userId, WsEnvelope env) {
        List<Channel> channels = sessionRegistry.getChannels(userId);
        if (channels == null || channels.isEmpty()) {
            return;
        }
        for (Channel ch : channels) {
            if (ch == null || !ch.isActive()) {
                continue;
            }
            wsWriter.write(ch, env);
        }
    }

    private void persistCallFailed(long callId, long callerUserId, long calleeUserId, String failReason) {
        LocalDateTime now = LocalDateTime.now();
        CompletableFuture.runAsync(() -> {
            CallRecordEntity record = new CallRecordEntity();
            record.setCallId(callId);
            record.setCallerUserId(callerUserId);
            record.setCalleeUserId(calleeUserId);
            record.setStatus(CallStatus.FAILED);
            record.setFailReason(failReason);
            record.setStartedAt(now);
            record.setEndedAt(now);
            callRecordService.save(record);
        }, dbExecutor);
    }

    private static String safeCallReason(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.length() > 64) {
            return s.substring(0, 64);
        }
        return s;
    }

    private boolean validateCallInvite(ChannelHandlerContext ctx, WsEnvelope msg) {
        if (msg.getTo() == null) {
            writeCallError(ctx, null, "missing_to", null, msg.getClientMsgId());
            return false;
        }
        String kind = msg.getCallKind();
        if (kind == null || kind.isBlank()) {
            msg.setCallKind("video");
        }
        if (!"video".equalsIgnoreCase(msg.getCallKind())) {
            writeCallError(ctx, null, "unsupported_call_kind", null, msg.getClientMsgId());
            return false;
        }
        if (msg.getSdp() == null || msg.getSdp().isBlank()) {
            writeCallError(ctx, null, "missing_sdp", null, msg.getClientMsgId());
            return false;
        }
        if (msg.getSdp().length() > MAX_SDP_LEN) {
            writeCallError(ctx, null, "sdp_too_long", null, msg.getClientMsgId());
            return false;
        }
        return true;
    }

    private boolean validateCallAccept(ChannelHandlerContext ctx, WsEnvelope msg) {
        if (!validateCallBasic(ctx, msg)) {
            return false;
        }
        if (msg.getSdp() == null || msg.getSdp().isBlank()) {
            writeCallError(ctx, msg.getCallId(), "missing_sdp", null, msg.getClientMsgId());
            return false;
        }
        if (msg.getSdp().length() > MAX_SDP_LEN) {
            writeCallError(ctx, msg.getCallId(), "sdp_too_long", null, msg.getClientMsgId());
            return false;
        }
        return true;
    }

    private boolean validateCallIce(ChannelHandlerContext ctx, WsEnvelope msg) {
        if (!validateCallBasic(ctx, msg)) {
            return false;
        }
        if (msg.getIceCandidate() == null || msg.getIceCandidate().isBlank()) {
            writeCallError(ctx, msg.getCallId(), "missing_ice_candidate", null, msg.getClientMsgId());
            return false;
        }
        if (msg.getIceCandidate().length() > MAX_ICE_LEN) {
            writeCallError(ctx, msg.getCallId(), "ice_candidate_too_long", null, msg.getClientMsgId());
            return false;
        }
        return true;
    }

    private boolean validateCallBasic(ChannelHandlerContext ctx, WsEnvelope msg) {
        if (msg.getCallId() == null || msg.getCallId() <= 0) {
            writeCallError(ctx, null, "missing_call_id", null, msg.getClientMsgId());
            return false;
        }
        return true;
    }

    private ChannelFuture writeCallError(ChannelHandlerContext ctx, Long callId, String reason, String callReason, String clientMsgId) {
        WsEnvelope err = new WsEnvelope();
        err.setType("CALL_ERROR");
        err.setCallId(callId);
        err.setClientMsgId(clientMsgId);
        err.setReason(reason);
        err.setCallReason(callReason);
        err.setTs(Instant.now().toEpochMilli());
        return wsWriter.write(ctx, err);
    }
}
