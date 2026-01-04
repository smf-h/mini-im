package com.miniim.gateway.ws;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.miniim.common.content.ForbiddenWordFilter;
import com.miniim.domain.entity.FriendRequestEntity;
import com.miniim.domain.enums.AckType;
import com.miniim.domain.enums.FriendRequestStatus;
import com.miniim.domain.service.FriendRequestService;
import com.miniim.gateway.session.SessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class WsFriendRequestHandler {

    private final SessionRegistry sessionRegistry;
    private final ClientMsgIdIdempotency idempotency;
    private final FriendRequestService friendRequestService;
    private final ForbiddenWordFilter forbiddenWordFilter;
    private final WsWriter wsWriter;
    @Qualifier("imDbExecutor")
    private final Executor dbExecutor;

    public void handle(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel channel = ctx.channel();
        Long fromUserId = channel.attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId == null) {
            wsWriter.writeError(ctx, "unauthorized", msg.getClientMsgId(), null);
            return;
        }
        if (!validate(ctx, msg)) {
            return;
        }

        Long toUserId = msg.getTo();
        String clientMsgId = msg.getClientMsgId();

        String key = idempotency.key(fromUserId.toString(), "FRIEND_REQUEST:" + clientMsgId);
        ClientMsgIdIdempotency.Claim newClaim = new ClientMsgIdIdempotency.Claim();
        String requestId = String.valueOf(IdWorker.getId());
        newClaim.setServerMsgId(requestId);
        ClientMsgIdIdempotency.Claim claim = idempotency.putIfAbsent(key, newClaim);
        if (claim != null) {
            wsWriter.writeAck(ctx, fromUserId, clientMsgId, claim.getServerMsgId(), AckType.SAVED.getDesc(), null);
            return;
        }

        FriendRequestEntity entity = new FriendRequestEntity();
        entity.setId(Long.valueOf(requestId));
        entity.setFromUserId(fromUserId);
        entity.setToUserId(toUserId);
        String sanitizedBody = forbiddenWordFilter.sanitize(msg.getBody());
        entity.setContent(sanitizedBody);
        entity.setStatus(FriendRequestStatus.PENDING);

        CompletableFuture<Boolean> saveFuture = CompletableFuture
                .supplyAsync(() -> friendRequestService.save(entity), dbExecutor)
                .orTimeout(3, TimeUnit.SECONDS);

        saveFuture.whenComplete((ok, error) -> {
            if (error != null || ok == null || !ok) {
                idempotency.remove(key);
                log.error("save friend request failed: {}", error == null ? "save_returned_false" : error.toString());
                ctx.executor().execute(() -> wsWriter.writeError(ctx, "internal_error", clientMsgId, requestId));
                return;
            }

            ctx.executor().execute(() -> wsWriter.writeAck(ctx, fromUserId, clientMsgId, requestId, AckType.SAVED.getDesc(), sanitizedBody));

            WsEnvelope out = new WsEnvelope();
            out.setType("FRIEND_REQUEST");
            out.setFrom(fromUserId);
            out.setTo(toUserId);
            out.setClientMsgId(clientMsgId);
            out.setServerMsgId(requestId);
            out.setBody(sanitizedBody);
            out.setTs(Instant.now().toEpochMilli());
            for (Channel chTo : sessionRegistry.getChannels(toUserId)) {
                if (chTo == null || !chTo.isActive()) {
                    continue;
                }
                wsWriter.write(chTo, out);
            }
        });
    }

    private boolean validate(ChannelHandlerContext ctx, WsEnvelope msg) {
        if (msg.getClientMsgId() == null || msg.getClientMsgId().isBlank()) {
            wsWriter.writeError(ctx, "missing_msg_id", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getTo() == null) {
            wsWriter.writeError(ctx, "missing_to", msg.getClientMsgId(), null);
            return false;
        }
        Long fromUserId = ctx.channel().attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId != null && msg.getTo().equals(fromUserId)) {
            wsWriter.writeError(ctx, "cannot_send_to_self", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getBody() != null && msg.getBody().length() > 256) {
            wsWriter.writeError(ctx, "body_too_long", msg.getClientMsgId(), null);
            return false;
        }
        return true;
    }
}
