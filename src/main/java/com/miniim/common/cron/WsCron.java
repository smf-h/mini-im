package com.miniim.common.cron;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniim.domain.entity.MessageEntity;
import com.miniim.domain.enums.MessageStatus;
import com.miniim.domain.mapper.MessageMapper;
import com.miniim.domain.service.MessageService;
import com.miniim.gateway.session.SessionRegistry;
import com.miniim.gateway.ws.WsEnvelope;
import com.miniim.gateway.ws.WsFrameHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class WsCron {

    private final MessageService messageService;
    private final SessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;

    @Value("${im.message.ack-timeout-ms:5000}")
    private long ackTimeoutMs;

    /**
     * 示例：定时扫描“需要补发/待处理”的消息。
     *
     * <p>这里用 DROPPED 作为示例（你当前单聊：对方离线就落库为 DROPPED）。
     * 真正补发一般建议在用户 AUTH 成功后触发；定时扫库适合作为兜底。</p>
     */
    @Scheduled(fixedDelayString = "${im.cron.scan-dropped.fixed-delay-ms:5000}")
    public void scanDroppedMessages() {
        LocalDateTime now = LocalDateTime.now();
        // 轻微延迟窗口，避免扫到“刚落库还在处理”的消息（按需调整/删除）
        LocalDateTime cutoff = now.minusSeconds(1);
        LocalDateTime retry = now.minusNanos(ackTimeoutMs * 1_000_000L);

        LambdaQueryWrapper<MessageEntity> qw = new LambdaQueryWrapper<>();
        qw.eq(MessageEntity::getStatus, MessageStatus.SAVED)
                .lt(MessageEntity::getCreatedAt, cutoff)
                .lt(MessageEntity::getUpdatedAt, retry)
                .orderByAsc(MessageEntity::getId)
                .last("limit 100");

        List<MessageEntity> list = messageService.list(qw);

        if (list.isEmpty()) {
            return;
        }
        // TODO: 这里通常会：
        for (MessageEntity msg : list) {
            Long toUserId = msg.getToUserId();
            Channel channelTo=sessionRegistry.getChannel(toUserId);
            if (channelTo == null||!channelTo.isActive()) {
                LambdaUpdateWrapper<MessageEntity> updateWrapper = new LambdaUpdateWrapper<>();
                updateWrapper.eq(MessageEntity::getId, msg.getId());
                updateWrapper.set(MessageEntity::getStatus, MessageStatus.DROPPED);
                messageService.update(updateWrapper);
                continue;
            }
            WsEnvelope envelope = new WsEnvelope();
            envelope.setTo(toUserId);
            envelope.setFrom(msg.getFromUserId());
            envelope.setBody(msg.getContent());
            envelope.setClientMsgId(msg.getClientMsgId());
            envelope.setType(msg.getChatType().getDesc());
            envelope.setServerMsgId(msg.getServerMsgId());
            envelope.setTs(Instant.now().toEpochMilli());
            channelTo.eventLoop().execute(()-> {
                try {
                     String json = objectMapper.writeValueAsString(envelope);
                    ChannelFuture f = channelTo.writeAndFlush(new TextWebSocketFrame(json));
                } catch (JsonProcessingException e) {
                    channelTo.newFailedFuture(e);
                    throw new RuntimeException(e);
                }
            });
        }
        // 1) 判断 toUserId 是否在线（SessionRegistry/Redis online 表）
        // 2) 在线则按 id ASC 逐条/小批投递
        // 3) 投递成功后批量更新为 DELIVERED / 或进入 WAITING/RETRY 机制
    }
}

