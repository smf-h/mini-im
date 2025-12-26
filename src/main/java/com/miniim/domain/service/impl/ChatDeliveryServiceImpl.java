package com.miniim.domain.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniim.domain.entity.MessageAckEntity;
import com.miniim.domain.entity.MessageEntity;
import com.miniim.domain.enums.AckType;
import com.miniim.domain.enums.MessageStatus;
import com.miniim.domain.service.ChatDeliveryService;
import com.miniim.domain.service.MessageAckService;
import com.miniim.domain.service.MessageService;
import com.miniim.gateway.session.SessionRegistry;
import com.miniim.gateway.ws.WsEnvelope;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatDeliveryServiceImpl implements ChatDeliveryService {

    private final MessageService messageService;
    private final MessageAckService messageAckService;
    private final SessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;

    @Override
    public boolean handleReceiverAck(Long fromUserId, Long toUserId, String clientMsgId, String serverMsgId, String ackTypeStr) {
        if (StrUtil.isBlank(ackTypeStr)) return false;
        AckType ackType;
        if ("RECEIVED".equalsIgnoreCase(ackTypeStr)) {
            ackType = AckType.DELIVERED; // 兼容别名
        } else if ("DELIVERED".equalsIgnoreCase(ackTypeStr)) {
            ackType = AckType.DELIVERED;
        } else if ("READ".equalsIgnoreCase(ackTypeStr)) {
            ackType = AckType.READ;
        } else {
            return false;
        }
        // 定位消息：优先用 serverMsgId，再退回 clientMsgId+fromUserId
        MessageEntity msg = null;
        if (StrUtil.isNotBlank(serverMsgId)) {
            try {
                long id = Long.parseLong(serverMsgId);
                msg = messageService.getById(id);
            } catch (NumberFormatException ignore) {}
        }
        if (msg == null && StrUtil.isNotBlank(clientMsgId)) {
            msg = messageService.getOne(new LambdaQueryWrapper<MessageEntity>()
                    .eq(MessageEntity::getClientMsgId, clientMsgId)
                    .eq(MessageEntity::getFromUserId, toUserId) // 原发送方
            );
        }
        if (msg == null) return false;

        // 保存 ACK（幂等由唯一索引保障）
        MessageAckEntity ack = new MessageAckEntity();
        ack.setMessageId(msg.getId());
        ack.setUserId(fromUserId);
        ack.setAckType(ackType);
        try { messageAckService.save(ack); } catch (Exception ignore) {}

        // 推进消息状态
        MessageStatus newStatus = (ackType == AckType.READ) ? MessageStatus.READ : MessageStatus.DELIVERED;
        messageService.update(new MessageEntity(), new LambdaUpdateWrapper<MessageEntity>()
                .eq(MessageEntity::getId, msg.getId())
                .set(MessageEntity::getStatus, newStatus)
        );
        return true;
    }

    @Override
    public void deliverPendingForUser(Long userId) {
        Channel ch = sessionRegistry.getChannel(userId);
        if (ch == null || !ch.isActive()) return;
        List<MessageEntity> list = messageService.list(new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getToUserId, userId)
                .in(MessageEntity::getStatus, MessageStatus.SAVED, MessageStatus.DROPPED)
                .orderByAsc(MessageEntity::getId)
                .last("limit 100")
        );
        for (MessageEntity e : list) {
            WsEnvelope env = new WsEnvelope();
            env.type = "SINGLE_CHAT"; // TODO: 根据 e.getChatType() 决定
            env.from = e.getFromUserId();
            env.to = e.getToUserId();
            env.clientMsgId = e.getClientMsgId();
            env.serverMsgId = e.getServerMsgId();
            env.msgType = e.getMsgType() == null ? "TEXT" : e.getMsgType().name();
            env.body = e.getContent();
            env.ts = Instant.now().toEpochMilli();
            try {
                ch.writeAndFlush(new TextWebSocketFrame(objectMapper.writeValueAsString(env)));
            } catch (Exception ex) {
                log.warn("deliverPending write fail: {}", ex.toString());
            }
        }
    }
}
