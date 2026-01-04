package com.miniim.gateway.ws;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.miniim.common.content.ForbiddenWordFilter;
import com.miniim.domain.entity.MessageEntity;
import com.miniim.domain.enums.AckType;
import com.miniim.domain.enums.ChatType;
import com.miniim.domain.enums.MessageStatus;
import com.miniim.domain.enums.MessageType;
import com.miniim.domain.service.MessageService;
import com.miniim.domain.service.SingleChatMemberService;
import com.miniim.domain.service.SingleChatService;
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

@Component
@RequiredArgsConstructor
@Slf4j
public class WsSingleChatHandler {

    private static final int MAX_BODY_LEN = 4096;

    private final SessionRegistry sessionRegistry;
    private final ClientMsgIdIdempotency idempotency;
    private final MessageService messageService;
    private final SingleChatService singleChatService;
    private final SingleChatMemberService singleChatMemberService;
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
            wsWriter.writeError(ctx, "invalid_single_chat", msg.getClientMsgId(), null);
            return;
        }

        Long toUserId = msg.getTo();
        if (toUserId.equals(fromUserId)) {
            wsWriter.writeError(ctx, "cannot_send_to_self", msg.getClientMsgId(), null);
            return;
        }

        List<Channel> channelsTo = sessionRegistry.getChannels(toUserId);
        final boolean dropped = channelsTo.stream().noneMatch(ch -> ch != null && ch.isActive());

        final WsEnvelope base = BeanUtil.toBean(msg, WsEnvelope.class);
        base.setFrom(fromUserId);
        base.setTs(Instant.now().toEpochMilli());
        base.setBody(forbiddenWordFilter.sanitize(base.getBody()));

        Long user1Id = Math.min(fromUserId, toUserId);
        Long user2Id = Math.max(fromUserId, toUserId);
        Long messageId = IdWorker.getId();
        String serverMsgId = String.valueOf(messageId);

        String key = idempotency.key(fromUserId.toString(), msg.getClientMsgId());
        ClientMsgIdIdempotency.Claim newClaim = new ClientMsgIdIdempotency.Claim();
        newClaim.setServerMsgId(serverMsgId);
        ClientMsgIdIdempotency.Claim claim = idempotency.putIfAbsent(key, newClaim);
        if (claim != null) {
            wsWriter.writeAck(ctx, fromUserId, msg.getClientMsgId(), claim.getServerMsgId(), AckType.SAVED.getDesc(), null);
            return;
        }

        MessageEntity messageEntity = new MessageEntity();
        messageEntity.setId(messageId);
        messageEntity.setServerMsgId(serverMsgId);
        messageEntity.setChatType(ChatType.SINGLE);
        messageEntity.setFromUserId(fromUserId);
        messageEntity.setToUserId(toUserId);
        MessageType messageType = MessageType.fromString(msg.getMsgType());
        messageEntity.setMsgType(messageType == null ? MessageType.TEXT : messageType);
        messageEntity.setStatus(MessageStatus.SAVED);
        messageEntity.setContent(base.getBody());
        messageEntity.setClientMsgId(msg.getClientMsgId());
        base.setServerMsgId(serverMsgId);

        CompletableFuture<Long> saveFuture = CompletableFuture.supplyAsync(() -> {
            Long singleChatId = singleChatService.getOrCreateSingleChatId(user1Id, user2Id);
            messageEntity.setSingleChatId(singleChatId);
            singleChatMemberService.ensureMembers(singleChatId, fromUserId, toUserId);
            messageService.save(messageEntity);
            singleChatService.update(new LambdaUpdateWrapper<com.miniim.domain.entity.SingleChatEntity>()
                    .eq(com.miniim.domain.entity.SingleChatEntity::getId, singleChatId)
                    .set(com.miniim.domain.entity.SingleChatEntity::getUpdatedAt, LocalDateTime.now()));
            return messageEntity.getId();
        }, dbExecutor).orTimeout(3, TimeUnit.SECONDS);

        saveFuture.whenComplete((result, error) -> {
            if (error != null) {
                log.error("save message failed: {}", error.toString());
                idempotency.remove(key);
                ctx.executor().execute(() -> wsWriter.writeError(ctx, "internal_error", msg.getClientMsgId(), serverMsgId));
                return;
            }

            ctx.executor().execute(() -> wsWriter.writeAck(ctx, fromUserId, base.getClientMsgId(), serverMsgId, AckType.SAVED.getDesc(), base.getBody()));

            if (dropped) {
                return;
            }

            for (Channel chTo : channelsTo) {
                if (chTo == null || !chTo.isActive()) {
                    continue;
                }
                ChannelFuture future;
                try {
                    WsEnvelope out = BeanUtil.toBean(base, WsEnvelope.class);
                    out.setType("SINGLE_CHAT");
                    future = wsWriter.write(chTo, out);
                } catch (Exception e) {
                    ctx.executor().execute(() -> wsWriter.writeError(ctx, "internal_error", base.getClientMsgId(), serverMsgId));
                    continue;
                }
                future.addListener(f -> {
                    if (!f.isSuccess()) {
                        log.error("deliver message to user {} failed: {}", toUserId, f.cause() == null ? "unknown" : f.cause().toString());
                        ctx.executor().execute(() -> wsWriter.writeError(ctx, "deliver_failed", base.getClientMsgId(), serverMsgId));
                    }
                });
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
        if (msg.getBody() == null || msg.getBody().isBlank()) {
            wsWriter.writeError(ctx, "missing_body", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getBody().length() > MAX_BODY_LEN) {
            wsWriter.writeError(ctx, "body_too_long", msg.getClientMsgId(), null);
            return false;
        }
        return true;
    }
}
