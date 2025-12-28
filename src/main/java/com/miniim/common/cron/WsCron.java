package com.miniim.common.cron;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniim.domain.entity.MessageEntity;
import com.miniim.domain.mapper.MessageMapper;
import com.miniim.domain.service.SingleChatMemberService;
import com.miniim.gateway.session.SessionRegistry;
import com.miniim.gateway.ws.WsEnvelope;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 可选兜底补发：基于“成员游标(last_delivered_msg_id)”拉取未投递区间。
 *
 * <p>默认关闭，避免开发/调试阶段产生重复投递与日志噪声。</p>
 */
@ConditionalOnProperty(name = "im.cron.resend.enabled", havingValue = "true")
@Component
@RequiredArgsConstructor
@Slf4j
public class WsCron {

    private final MessageMapper messageMapper;
    private final SessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;
    private final SingleChatMemberService singleChatMemberService;

    @Scheduled(fixedDelayString = "${im.cron.resend.fixed-delay-ms:30000}")
    public void resendPendingMessagesForOnlineUsers() {
        List<Long> onlineUserIds = sessionRegistry.getOnlineUserIds();
        if (onlineUserIds.isEmpty()) {
            return;
        }
        for (Long userId : onlineUserIds) {
            if (userId == null || userId <= 0) {
                continue;
            }
            resendForUser(userId);
        }
    }

    private void resendForUser(long userId) {
        singleChatMemberService.ensureMembersForUser(userId);

        List<MessageEntity> singleList = messageMapper.selectPendingSingleChatMessagesForUser(userId, 200);
        List<MessageEntity> groupList = messageMapper.selectPendingGroupMessagesForUser(userId, 200);
        if (singleList.isEmpty() && groupList.isEmpty()) {
            return;
        }

        List<Channel> channels = sessionRegistry.getChannels(userId);
        if (channels.isEmpty()) {
            return;
        }

        for (Channel target : channels) {
            if (target == null || !target.isActive()) {
                continue;
            }
            for (MessageEntity msg : singleList) {
                write(target, userId, msg);
            }
            for (MessageEntity msg : groupList) {
                write(target, userId, msg);
            }
        }
    }

    private void write(Channel target, long userId, MessageEntity msg) {
        if (msg == null) {
            return;
        }
        WsEnvelope envelope = new WsEnvelope();
        envelope.setType(msg.getChatType() == null ? null : msg.getChatType().getDesc());
        envelope.setFrom(msg.getFromUserId());
        envelope.setTo(userId);
        envelope.setGroupId(msg.getGroupId());
        envelope.setBody(msg.getContent());
        envelope.setClientMsgId(msg.getClientMsgId());
        envelope.setServerMsgId(msg.getServerMsgId());
        envelope.setMsgType(msg.getMsgType() == null ? null : msg.getMsgType().getDesc());
        envelope.setTs(toEpochMilli(msg.getCreatedAt()));

        target.eventLoop().execute(() -> {
            try {
                String json = objectMapper.writeValueAsString(envelope);
                target.writeAndFlush(new TextWebSocketFrame(json));
            } catch (Exception e) {
                log.warn("ws resend serialize/write failed: {}", e.toString());
            }
        });
    }

    private static long toEpochMilli(LocalDateTime time) {
        if (time == null) {
            return Instant.now().toEpochMilli();
        }
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
