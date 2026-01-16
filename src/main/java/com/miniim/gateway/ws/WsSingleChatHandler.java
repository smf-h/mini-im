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
import com.miniim.gateway.config.WsPerfTraceProperties;
import com.miniim.gateway.config.WsInboundQueueProperties;
import com.miniim.gateway.config.WsSingleChatTwoPhaseProperties;
import com.miniim.gateway.config.WsSingleChatUpdatedAtDebounceProperties;
import com.miniim.gateway.session.SessionRegistry;
import com.miniim.gateway.ws.twophase.WsSingleChatTwoPhaseProducer;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

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
    private final SingleChatUpdatedAtDebouncer updatedAtDebouncer;
    private final WsSingleChatUpdatedAtDebounceProperties updatedAtProps;
    private final WsPerfTraceProperties perfTraceProps;
    private final WsInboundQueueProperties inboundQueueProps;
    private final WsSingleChatTwoPhaseProperties twoPhaseProps;
    private final WsSingleChatTwoPhaseProducer twoPhaseProducer;
    @Qualifier("imDbExecutor")
    private final Executor dbExecutor;
    @Qualifier("imPostDbExecutor")
    private final Executor postDbExecutor;

    private record SaveResult(boolean saved, long singleChatId) {
    }

    public void handle(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel channel = ctx.channel();
        Long fromUserId = channel.attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId == null) {
            wsWriter.writeError(ctx, "unauthorized", msg.getClientMsgId(), null);
            return;
        }
        long enqueuedAtNs = System.nanoTime();
        if (inboundQueueProps != null && inboundQueueProps.enabledEffective()) {
            int maxPending = inboundQueueProps.maxPendingPerConnEffective();
            WsChannelSerialQueue.tryEnqueue(channel, () -> handleSerial(ctx, msg, fromUserId, enqueuedAtNs), maxPending)
                    .exceptionally(e -> {
                        wsWriter.writeError(ctx, "server_busy", msg.getClientMsgId(), null);
                        return null;
                    });
        } else {
            WsChannelSerialQueue.enqueue(channel, () -> handleSerial(ctx, msg, fromUserId, enqueuedAtNs));
        }
    }

    private CompletionStage<Void> handleSerial(ChannelHandlerContext ctx, WsEnvelope msg, long fromUserId, long enqueuedAtNs) {
        CompletableFuture<Void> done = new CompletableFuture<>();

        Channel channel = ctx.channel();
        long serialStartNs = System.nanoTime();
        long queueNs = serialStartNs - enqueuedAtNs;

        if (!validate(ctx, msg)) {
            done.complete(null);
            return done;
        }

        Long toUserId = msg.getTo();
        if (toUserId.equals(fromUserId)) {
            wsWriter.writeError(ctx, "cannot_send_to_self", msg.getClientMsgId(), null);
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

        if (twoPhaseProps != null && twoPhaseProps.enabledEffective()) {
            WsSingleChatTwoPhaseProducer.EnqueueResult res = twoPhaseProducer.enqueueAccepted(
                    fromUserId,
                    toUserId,
                    msg.getClientMsgId(),
                    serverMsgId,
                    msg.getMsgType(),
                    base.getBody(),
                    base.getTs() == null ? System.currentTimeMillis() : base.getTs()
            );

            if (!res.isOk()) {
                if (twoPhaseProps.failOpenEffective()) {
                    // fallback to legacy path below
                } else {
                    wsWriter.writeError(channel, "server_busy", msg.getClientMsgId(), serverMsgId);
                    done.complete(null);
                    return done;
                }
            } else {
                wsWriter.writeAck(channel, fromUserId, base.getClientMsgId(), res.getServerMsgId(), "accepted", base.getBody());
                done.complete(null);
                return done;
            }
        }

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

        final long dbSubmitNs = System.nanoTime();
        final long[] perfNs = new long[8];
        // 0=dbQueue,1=idem,2=getChatId,3=ensureMembers(removed),4=saveMsg,5=updateChat,6=dbToEventLoop,7=push
        CompletableFuture<SaveResult> saveFuture;
        try {
            saveFuture = CompletableFuture.supplyAsync(() -> {
            long dbStartNs = System.nanoTime();
            perfNs[0] = dbStartNs - dbSubmitNs;

            ClientMsgIdIdempotency.Claim existed = idempotency.putIfAbsent(idemKey, newClaim);
            perfNs[1] = System.nanoTime() - dbStartNs;
            if (existed != null) {
                wsWriter.writeAck(channel, fromUserId, msg.getClientMsgId(), existed.getServerMsgId(), AckType.SAVED.getDesc(), null);
                return new SaveResult(false, 0L);
            }

            long t = System.nanoTime();
            Long singleChatId = singleChatService.getOrCreateSingleChatId(user1Id, user2Id);
            perfNs[2] = System.nanoTime() - t;
            messageEntity.setSingleChatId(singleChatId);

            // member 行不再是“发送可达”的必需条件：从发送热路径移出（列表/补发/ACK 兜底补齐）
            perfNs[3] = 0L;

            t = System.nanoTime();
            messageService.save(messageEntity);
            perfNs[4] = System.nanoTime() - t;

            // updated_at 从主链路拆出（异步 best-effort），此处不再阻塞 ACK/投递。
            perfNs[5] = 0L;
            return new SaveResult(true, singleChatId);
            }, dbExecutor).orTimeout(3, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            wsWriter.writeError(channel, "server_busy", msg.getClientMsgId(), serverMsgId);
            done.complete(null);
            return done;
        }

        saveFuture.whenComplete((result, error) -> {
            long dbDoneNs = System.nanoTime();
            if (error != null) {
                log.error("save message failed: {}", error.toString());
                try {
                    idempotency.remove(idemKey);
                } catch (Exception e) {
                    log.debug("idem cleanup failed: key={}, err={}", idemKey, e.toString());
                }
                wsWriter.writeError(channel, "internal_error", msg.getClientMsgId(), serverMsgId);
                done.complete(null);
                return;
            }
            if (result == null || !result.saved()) {
                done.complete(null);
                return;
            }
            long singleChatId = result.singleChatId();

            CompletableFuture<Long> dbToEventLoopNsFuture = new CompletableFuture<>();
            long ackEnqueuedAtNs = System.nanoTime();
            wsWriter.writeAck(channel, fromUserId, base.getClientMsgId(), serverMsgId, AckType.SAVED.getDesc(), base.getBody(),
                    delayNs -> dbToEventLoopNsFuture.complete((ackEnqueuedAtNs - dbDoneNs) + delayNs));

            WsEnvelope out = BeanUtil.toBean(base, WsEnvelope.class);
            out.setType("SINGLE_CHAT");
            long pushStartNs = System.nanoTime();
            try {
                wsPushService.pushToUser(toUserId, out);
            } catch (Exception e) {
                log.debug("push failed: {}", e.toString());
            }
            perfNs[7] = System.nanoTime() - pushStartNs;

            boolean doUpdateChat = singleChatId > 0
                    && (updatedAtDebouncer == null || updatedAtDebouncer.shouldUpdateNow(singleChatId, System.currentTimeMillis()));
            if (doUpdateChat) {
                scheduleUpdateChatUpdatedAt(singleChatId);
            }

            dbToEventLoopNsFuture.whenComplete((ns, e) -> {
                perfNs[6] = ns == null ? 0L : ns;
                maybeLogPerf(ctx, fromUserId, toUserId, msg.getClientMsgId(), serverMsgId, queueNs, perfNs, enqueuedAtNs);
                done.complete(null);
            });
        });

        return done;
    }

    private void maybeLogPerf(ChannelHandlerContext ctx,
                              long fromUserId,
                              long toUserId,
                              String clientMsgId,
                              String serverMsgId,
                              long queueNs,
                              long[] perfNs,
                              long enqueuedAtNs) {
        if (perfTraceProps == null || !perfTraceProps.enabledEffective()) {
            return;
        }
        long nowNs = System.nanoTime();
        long totalMs = TimeUnit.NANOSECONDS.toMillis(nowNs - enqueuedAtNs);
        long slowMs = perfTraceProps.slowMsEffective();
        double sampleRate = perfTraceProps.sampleRateEffective();
        boolean sampled = sampleRate > 0 && ThreadLocalRandom.current().nextDouble() < sampleRate;
        if (totalMs < slowMs && !sampled) {
            return;
        }

        String cid = ctx.channel().attr(SessionRegistry.ATTR_CONN_ID).get();
        log.info("ws_perf single_chat from={} to={} cid={} clientMsgId={} serverMsgId={} totalMs={} queueMs={} dbQueueMs={} idemMs={} getChatMs={} ensureMembersMs={} saveMsgMs={} updateChatMs={} dbToEventLoopMs={} pushMs={}",
                fromUserId, toUserId, cid, clientMsgId, serverMsgId,
                totalMs,
                TimeUnit.NANOSECONDS.toMillis(queueNs),
                TimeUnit.NANOSECONDS.toMillis(perfNs[0]),
                TimeUnit.NANOSECONDS.toMillis(perfNs[1]),
                TimeUnit.NANOSECONDS.toMillis(perfNs[2]),
                TimeUnit.NANOSECONDS.toMillis(perfNs[3]),
                TimeUnit.NANOSECONDS.toMillis(perfNs[4]),
                TimeUnit.NANOSECONDS.toMillis(perfNs[5]),
                TimeUnit.NANOSECONDS.toMillis(perfNs[6]),
                TimeUnit.NANOSECONDS.toMillis(perfNs[7]));
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

    private void scheduleUpdateChatUpdatedAt(long singleChatId) {
        boolean syncUpdate = updatedAtProps != null && updatedAtProps.syncUpdateEffective();
        if (syncUpdate) {
            try {
                singleChatService.update(new LambdaUpdateWrapper<com.miniim.domain.entity.SingleChatEntity>()
                        .eq(com.miniim.domain.entity.SingleChatEntity::getId, singleChatId)
                        .set(com.miniim.domain.entity.SingleChatEntity::getUpdatedAt, LocalDateTime.now()));
            } catch (Exception e) {
                log.debug("update single_chat.updated_at sync failed: chatId={}, err={}", singleChatId, e.toString());
            }
            return;
        }

        Executor executor = postDbExecutor;
        if (executor == null) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    singleChatService.update(new LambdaUpdateWrapper<com.miniim.domain.entity.SingleChatEntity>()
                            .eq(com.miniim.domain.entity.SingleChatEntity::getId, singleChatId)
                            .set(com.miniim.domain.entity.SingleChatEntity::getUpdatedAt, LocalDateTime.now()));
                } catch (Exception e) {
                    log.debug("update single_chat.updated_at async failed: chatId={}, err={}", singleChatId, e.toString());
                }
            });
        } catch (RejectedExecutionException e) {
            log.debug("update single_chat.updated_at dropped: post-db executor busy, chatId={}", singleChatId);
        } catch (Exception e) {
            log.debug("update single_chat.updated_at schedule failed: chatId={}, err={}", singleChatId, e.toString());
        }
    }
}
