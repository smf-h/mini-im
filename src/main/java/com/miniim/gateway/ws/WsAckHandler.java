package com.miniim.gateway.ws;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.miniim.domain.entity.MessageEntity;
import com.miniim.domain.enums.AckType;
import com.miniim.domain.enums.ChatType;
import com.miniim.domain.mapper.GroupMemberMapper;
import com.miniim.domain.service.MessageService;
import com.miniim.domain.service.SingleChatMemberService;
import com.miniim.gateway.config.WsAckBatchProperties;
import com.miniim.gateway.session.SessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
@Slf4j
public class WsAckHandler {

    private final SessionRegistry sessionRegistry;
    private final MessageService messageService;
    private final SingleChatMemberService singleChatMemberService;
    private final GroupMemberMapper groupMemberMapper;
    private final WsPushService wsPushService;
    private final WsWriter wsWriter;
    private final WsAckBatchProperties ackBatchProperties;
    @Qualifier("imAckExecutor")
    private final Executor ackExecutor;

    public void handle(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel channel = ctx.channel();
        Long fromUserId = channel.attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId == null) {
            wsWriter.writeError(ctx, "unauthorized", msg.getClientMsgId(), msg.getServerMsgId());
            ctx.close();
            return;
        }

        if (!validateAck(ctx, msg)) {
            return;
        }

        msg.setFrom(fromUserId);
        msg.setTs(Instant.now().toEpochMilli());

        String ackTypeRaw = msg.getAckType();
        if (isDeliveredAck(ackTypeRaw)) {
            handleAdvanceCursor(ctx, msg, fromUserId, AckType.DELIVERED);
        } else if (isReadAck(ackTypeRaw)) {
            handleAdvanceCursor(ctx, msg, fromUserId, AckType.READ);
        }
    }

    private void handleAdvanceCursor(ChannelHandlerContext ctx, WsEnvelope msg, long ackUserId, AckType ackType) {
        String serverMsgId = msg.getServerMsgId();
        if (serverMsgId == null || serverMsgId.isBlank()) {
            return;
        }
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    MessageEntity messageEntity = findMessageByAck(ctx, msg, ackUserId);
                    if (messageEntity == null || messageEntity.getId() == null || messageEntity.getChatType() == null) {
                        return;
                    }
                    ackBatcher.enqueueResolved(messageEntity, ackUserId, ackType);
                } catch (Exception e) {
                    log.debug("ws ack resolve/enqueue failed: {}", e.toString());
                }
            }, ackExecutor).orTimeout(3, TimeUnit.SECONDS).exceptionally(e -> {
                log.error("ws ack failed: {}", e.toString());
                return null;
            });
        } catch (RejectedExecutionException e) {
            log.warn("ws ack rejected: {}", e.toString());
        } catch (Exception e) {
            log.debug("ws ack schedule failed: {}", e.toString());
        }
    }

    private MessageEntity findMessageByAck(ChannelHandlerContext ctx, WsEnvelope msg, long ackUserId) {
        String serverMsgId = msg.getServerMsgId();
        if (serverMsgId == null || serverMsgId.isBlank()) {
            ctx.executor().execute(() -> wsWriter.writeError(ctx, "missing_server_msg_id", msg.getClientMsgId(), null));
            return null;
        }

        MessageEntity entity = null;
        try {
            long id = Long.parseLong(serverMsgId);
            entity = messageService.getById(id);
        } catch (NumberFormatException ignore) {
            // serverMsgId 可能不是数字（例如使用自定义 serverMsgId）；走下面按 serverMsgId 查询的兜底逻辑
        }
        if (entity == null) {
            entity = messageService.getOne(new LambdaQueryWrapper<MessageEntity>()
                    .eq(MessageEntity::getServerMsgId, serverMsgId)
                    .last("limit 1"));
        }
        if (entity == null) {
            ctx.executor().execute(() -> wsWriter.writeError(ctx, "message_not_found", msg.getClientMsgId(), serverMsgId));
            return null;
        }

        if (entity.getChatType() == ChatType.SINGLE) {
            Long toUserId = entity.getToUserId();
            if (toUserId == null || toUserId != ackUserId) {
                ctx.executor().execute(() -> wsWriter.writeError(ctx, "ack_not_allowed", msg.getClientMsgId(), serverMsgId));
                return null;
            }
        }
        return entity;
    }

    private boolean validateAck(ChannelHandlerContext ctx, WsEnvelope msg) {
        if (msg.getTo() == null) {
            wsWriter.writeError(ctx, "missing_to", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getAckType() == null || msg.getAckType().isBlank()) {
            wsWriter.writeError(ctx, "missing_ack_type", msg.getClientMsgId(), null);
            return false;
        }
        String ackType = msg.getAckType();
        if (!isDeliveredAck(ackType) && !isReadAck(ackType)) {
            wsWriter.writeError(ctx, "unknown_ack_type", msg.getClientMsgId(), msg.getServerMsgId());
            return false;
        }
        if ((msg.getClientMsgId() == null || msg.getClientMsgId().isBlank())
                && !(isDeliveredAck(ackType) || isReadAck(ackType))) {
            wsWriter.writeError(ctx, "missing_msg_id", msg.getClientMsgId(), null);
            return false;
        }
        if ((isDeliveredAck(ackType) || isReadAck(ackType))
                && (msg.getServerMsgId() == null || msg.getServerMsgId().isBlank())) {
            wsWriter.writeError(ctx, "missing_server_msg_id", msg.getClientMsgId(), null);
            return false;
        }
        return true;
    }

    private static boolean isDeliveredAck(String ackType) {
        if (ackType == null) {
            return false;
        }
        return "delivered".equalsIgnoreCase(ackType)
                ;
    }

    private static boolean isReadAck(String ackType) {
        if (ackType == null) {
            return false;
        }
        return "read".equalsIgnoreCase(ackType) || "ack_read".equalsIgnoreCase(ackType);
    }

    private final AckBatcher ackBatcher = new AckBatcher();

    private final class AckBatcher {
        private final Object lock = new Object();
        private final AtomicBoolean scheduled = new AtomicBoolean(false);
        private ScheduledExecutorService timer;
        private ScheduledFuture<?> future;

        private final Map<AckKey, PendingAck> pending = new HashMap<>();
        private final AckKey lookupKey = new AckKey();

        private ScheduledExecutorService ensureTimer() {
            ScheduledExecutorService t = timer;
            if (t != null) {
                return t;
            }
            if (!ackBatchProperties.batchEnabledEffective()) {
                return null;
            }
            synchronized (lock) {
                if (timer != null) {
                    return timer;
                }
                timer = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread th = new Thread(r, "im-ack-timer");
                    th.setDaemon(true);
                    return th;
                });
                return timer;
            }
        }

        void enqueueResolved(MessageEntity entity, long ackUserId, AckType ackType) {
            if (entity == null || entity.getId() == null || entity.getChatType() == null) {
                return;
            }
            Long msgSeq = entity.getMsgSeq();
            if (msgSeq == null || msgSeq <= 0) {
                return;
            }
            if (!ackBatchProperties.batchEnabledEffective()) {
                process(List.of(toPending(entity, ackUserId, ackType)));
                return;
            }
            long seq = msgSeq;
            ChatType chatType = entity.getChatType();

            Long chatId = null;
            if (chatType == ChatType.SINGLE) {
                chatId = entity.getSingleChatId();
            } else if (chatType == ChatType.GROUP) {
                chatId = entity.getGroupId();
            }
            if (chatId == null || chatId <= 0) {
                return;
            }

            synchronized (lock) {
                lookupKey.set(ackUserId, ackType, chatType, chatId);
                PendingAck p = pending.get(lookupKey);
                if (p == null) {
                    AckKey key = new AckKey();
                    key.set(ackUserId, ackType, chatType, chatId);
                    p = new PendingAck();
                    p.ackUserId = ackUserId;
                    p.ackType = ackType;
                    p.chatType = chatType;
                    p.chatId = chatId;
                    p.maxMsgSeq = seq;
                    p.maxServerMsgId = entity.getServerMsgId();
                    p.senderUserId = entity.getFromUserId();
                    pending.put(key, p);
                } else if (seq > p.maxMsgSeq) {
                    p.maxMsgSeq = seq;
                    p.maxServerMsgId = entity.getServerMsgId();
                    p.senderUserId = entity.getFromUserId();
                }

                ScheduledExecutorService t = ensureTimer();
                if (scheduled.compareAndSet(false, true) && t != null) {
                    int windowMs = ackBatchProperties.batchWindowMsEffective();
                    future = t.schedule(this::flush, windowMs, TimeUnit.MILLISECONDS);
                }
            }
        }

        private PendingAck toPending(MessageEntity entity, long ackUserId, AckType ackType) {
            PendingAck p = new PendingAck();
            p.ackUserId = ackUserId;
            p.ackType = ackType;
            p.chatType = entity.getChatType();
            if (p.chatType == ChatType.SINGLE) {
                p.chatId = entity.getSingleChatId() == null ? 0 : entity.getSingleChatId();
            } else if (p.chatType == ChatType.GROUP) {
                p.chatId = entity.getGroupId() == null ? 0 : entity.getGroupId();
            }
            Long msgSeq = entity.getMsgSeq();
            p.maxMsgSeq = msgSeq == null ? 0 : msgSeq;
            p.maxServerMsgId = entity.getServerMsgId();
            p.senderUserId = entity.getFromUserId();
            return p;
        }

        private void flush() {
            List<PendingAck> batch;
            synchronized (lock) {
                batch = new ArrayList<>(pending.values());
                pending.clear();
                scheduled.set(false);
                future = null;
            }

            if (batch.isEmpty()) {
                return;
            }

            try {
                CompletableFuture.runAsync(() -> process(batch), ackExecutor)
                        .orTimeout(3, TimeUnit.SECONDS)
                        .exceptionally(e -> {
                            log.error("ws ack batch failed: {}", e.toString());
                            return null;
                        });
            } catch (RejectedExecutionException e) {
                log.warn("ws ack batch rejected: {}", e.toString());
            } catch (Exception e) {
                log.debug("ws ack batch schedule failed: {}", e.toString());
            }
        }

        private void process(List<PendingAck> batch) {
            for (PendingAck p : batch) {
                if (p == null || p.chatId <= 0) {
                    continue;
                }
                if (p.chatType == ChatType.SINGLE) {
                    if (p.ackType == AckType.DELIVERED) {
                        singleChatMemberService.markDelivered(p.chatId, p.ackUserId, p.maxMsgSeq);
                    } else if (p.ackType == AckType.READ) {
                        singleChatMemberService.markRead(p.chatId, p.ackUserId, p.maxMsgSeq);
                    }

                    Long senderUserId = p.senderUserId;
                    if (senderUserId != null && senderUserId > 0 && senderUserId != p.ackUserId) {
                        WsEnvelope ack = new WsEnvelope();
                        ack.type = "ACK";
                        ack.from = p.ackUserId;
                        ack.to = senderUserId;
                        ack.serverMsgId = p.maxServerMsgId;
                        ack.msgSeq = p.maxMsgSeq;
                        ack.ackType = p.ackType.getDesc();
                        ack.ts = Instant.now().toEpochMilli();
                        wsPushService.pushToUser(senderUserId, ack);
                    }
                    continue;
                }

                if (p.chatType == ChatType.GROUP) {
                    if (p.ackType == AckType.DELIVERED) {
                        groupMemberMapper.markDeliveredSeq(p.chatId, p.ackUserId, p.maxMsgSeq);
                    } else if (p.ackType == AckType.READ) {
                        groupMemberMapper.markReadSeq(p.chatId, p.ackUserId, p.maxMsgSeq);
                    }
                }
            }
        }
    }

    private static final class AckKey {
        private long ackUserId;
        private AckType ackType;
        private ChatType chatType;
        private long chatId;
        private int hash;

        void set(long ackUserId, AckType ackType, ChatType chatType, long chatId) {
            this.ackUserId = ackUserId;
            this.ackType = ackType;
            this.chatType = chatType;
            this.chatId = chatId;

            int h = 17;
            h = 31 * h + Long.hashCode(ackUserId);
            h = 31 * h + (ackType == null ? 0 : (ackType.ordinal() + 1));
            h = 31 * h + (chatType == null ? 0 : (chatType.ordinal() + 1));
            h = 31 * h + Long.hashCode(chatId);
            this.hash = h;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof AckKey that)) {
                return false;
            }
            return ackUserId == that.ackUserId
                    && chatId == that.chatId
                    && ackType == that.ackType
                    && chatType == that.chatType;
        }
    }

    private static final class PendingAck {
        long ackUserId;
        AckType ackType;
        ChatType chatType;
        long chatId;
        long maxMsgSeq;
        String maxServerMsgId;
        Long senderUserId;
    }
}
