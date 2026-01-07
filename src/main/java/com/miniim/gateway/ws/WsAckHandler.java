package com.miniim.gateway.ws;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.miniim.domain.entity.MessageEntity;
import com.miniim.domain.enums.AckType;
import com.miniim.domain.enums.ChatType;
import com.miniim.domain.mapper.GroupMemberMapper;
import com.miniim.domain.service.MessageService;
import com.miniim.domain.service.SingleChatMemberService;
import com.miniim.gateway.session.SessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

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
    @Qualifier("imDbExecutor")
    private final Executor dbExecutor;

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
        CompletableFuture.runAsync(() -> {
            MessageEntity messageEntity = findMessageByAck(ctx, msg, ackUserId);
            if (messageEntity == null || messageEntity.getId() == null || messageEntity.getChatType() == null) {
                return;
            }

            long msgId = messageEntity.getId();
            ChatType chatType = messageEntity.getChatType();
            if (chatType == ChatType.SINGLE) {
                Long singleChatId = messageEntity.getSingleChatId();
                if (singleChatId == null || singleChatId <= 0) {
                    return;
                }
                if (ackType == AckType.DELIVERED) {
                    singleChatMemberService.markDelivered(singleChatId, ackUserId, msgId);
                } else if (ackType == AckType.READ) {
                    singleChatMemberService.markRead(singleChatId, ackUserId, msgId);
                }

                Long senderUserId = messageEntity.getFromUserId();
                if (senderUserId != null && senderUserId > 0 && senderUserId != ackUserId) {
                    WsEnvelope ack = new WsEnvelope();
                    ack.type = "ACK";
                    ack.from = ackUserId;
                    ack.to = senderUserId;
                    ack.serverMsgId = messageEntity.getServerMsgId();
                    ack.ackType = ackType.getDesc();
                    ack.ts = Instant.now().toEpochMilli();
                    wsPushService.pushToUser(senderUserId, ack);
                }
                return;
            }

            if (chatType == ChatType.GROUP) {
                Long groupId = messageEntity.getGroupId();
                if (groupId == null || groupId <= 0) {
                    return;
                }
                if (ackType == AckType.DELIVERED) {
                    groupMemberMapper.markDelivered(groupId, ackUserId, msgId);
                } else if (ackType == AckType.READ) {
                    groupMemberMapper.markRead(groupId, ackUserId, msgId);
                }
            }
        }, dbExecutor).orTimeout(3, TimeUnit.SECONDS).exceptionally(e -> {
            log.error("ws ack failed: {}", e.toString());
            return null;
        });
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
                || "received".equalsIgnoreCase(ackType)
                || "ack_receive".equalsIgnoreCase(ackType);
    }

    private static boolean isReadAck(String ackType) {
        if (ackType == null) {
            return false;
        }
        return "read".equalsIgnoreCase(ackType) || "ack_read".equalsIgnoreCase(ackType);
    }
}
