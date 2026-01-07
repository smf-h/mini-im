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
import java.util.concurrent.CompletionStage;
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
    private final WsPushService wsPushService;
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
        WsChannelSerialQueue.enqueue(channel, () -> handleSerial(ctx, msg, fromUserId));
    }

    private CompletionStage<Void> handleSerial(ChannelHandlerContext ctx, WsEnvelope msg, long fromUserId) {
        CompletableFuture<Void> done = new CompletableFuture<>();

        if (!validate(ctx, msg)) {
            done.complete(null);
            return done;
        }

        Long toUserId = msg.getTo();
        if (toUserId.equals(fromUserId)) {
            ctx.executor().execute(() -> wsWriter.writeError(ctx, "cannot_send_to_self", msg.getClientMsgId(), null));
            done.complete(null);
            return done;
        }

        final WsEnvelope base = BeanUtil.toBean(msg, WsEnvelope.class);
        base.setFrom(fromUserId);
        base.setTs(Instant.now().toEpochMilli());
        base.setBody(forbiddenWordFilter.sanitize(base.getBody()));

        Long user1Id = Math.min(fromUserId, toUserId);
        Long user2Id = Math.max(fromUserId, toUserId);
        Long messageId = IdWorker.getId();
        String serverMsgId = String.valueOf(messageId);
        base.setServerMsgId(serverMsgId);

        String idemKey = idempotency.key(fromUserId, "SINGLE_CHAT", msg.getClientMsgId());
        ClientMsgIdIdempotency.Claim newClaim = new ClientMsgIdIdempotency.Claim();
        newClaim.setServerMsgId(serverMsgId);

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

        CompletableFuture<Boolean> saveFuture = CompletableFuture.supplyAsync(() -> {
            ClientMsgIdIdempotency.Claim existed = idempotency.putIfAbsent(idemKey, newClaim);
            if (existed != null) {
                ctx.executor().execute(() -> wsWriter.writeAck(ctx, fromUserId, msg.getClientMsgId(), existed.getServerMsgId(), AckType.SAVED.getDesc(), null));
                return false;
            }

            Long singleChatId = singleChatService.getOrCreateSingleChatId(user1Id, user2Id);
            messageEntity.setSingleChatId(singleChatId);
            singleChatMemberService.ensureMembers(singleChatId, fromUserId, toUserId);
            messageService.save(messageEntity);
            singleChatService.update(new LambdaUpdateWrapper<com.miniim.domain.entity.SingleChatEntity>()
                    .eq(com.miniim.domain.entity.SingleChatEntity::getId, singleChatId)
                    .set(com.miniim.domain.entity.SingleChatEntity::getUpdatedAt, LocalDateTime.now()));
            return true;
        }, dbExecutor).orTimeout(3, TimeUnit.SECONDS);

        saveFuture.whenComplete((saved, error) -> {
            if (error != null) {
                log.error("save message failed: {}", error.toString());
                try {
                    idempotency.remove(idemKey);
                } catch (Exception e) {
                    log.debug("idem cleanup failed: key={}, err={}", idemKey, e.toString());
                }
                ctx.executor().execute(() -> wsWriter.writeError(ctx, "internal_error", msg.getClientMsgId(), serverMsgId));
                done.complete(null);
                return;
            }
            if (Boolean.FALSE.equals(saved)) {
                done.complete(null);
                return;
            }

            ctx.executor().execute(() -> wsWriter.writeAck(ctx, fromUserId, base.getClientMsgId(), serverMsgId, AckType.SAVED.getDesc(), base.getBody()));

            WsEnvelope out = BeanUtil.toBean(base, WsEnvelope.class);
            out.setType("SINGLE_CHAT");
            wsPushService.pushToUser(toUserId, out);
            done.complete(null);
        });

        return done;
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
