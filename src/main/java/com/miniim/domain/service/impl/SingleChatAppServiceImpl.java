package com.miniim.domain.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.miniim.domain.dto.single.HistoryResponse;
import com.miniim.domain.dto.single.MessageDTO;
import com.miniim.domain.dto.single.SendMessageResponse;
import com.miniim.domain.entity.MessageEntity;
import com.miniim.domain.entity.SingleChatEntity;
import com.miniim.domain.enums.ChatType;
import com.miniim.domain.enums.MessageStatus;
import com.miniim.domain.enums.MessageType;
import com.miniim.domain.service.MessageService;
import com.miniim.domain.service.SingleChatAppService;
import com.miniim.domain.service.SingleChatService;
import com.miniim.gateway.session.SessionRegistry;
import com.miniim.gateway.ws.ClientMsgIdIdempotency;
import com.miniim.gateway.ws.WsEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class SingleChatAppServiceImpl implements SingleChatAppService {

    private final SingleChatService singleChatService;
    private final MessageService messageService;
    private final ClientMsgIdIdempotency idempotency;
    private final SessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;

    public SingleChatAppServiceImpl(SingleChatService singleChatService,
                                    MessageService messageService,
                                    ClientMsgIdIdempotency idempotency,
                                    SessionRegistry sessionRegistry,
                                    ObjectMapper objectMapper) {
        this.singleChatService = singleChatService;
        this.messageService = messageService;
        this.idempotency = idempotency;
        this.sessionRegistry = sessionRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public SendMessageResponse sendText(Long fromUserId, Long toUserId, String clientMsgId, String content) {
        // TODO(smf-h): 需要加入更多校验（拉黑/禁言、内容审查等）
        Long u1 = Math.min(fromUserId, toUserId);
        Long u2 = Math.max(fromUserId, toUserId);
        Long singleChatId = singleChatService.getOrCreateSingleChatId(u1, u2);

        // 幂等占位
        String key = idempotency.key(String.valueOf(fromUserId), clientMsgId);
        ClientMsgIdIdempotency.Claim exist = idempotency.get(key);
        if (exist != null) {
            SendMessageResponse r = new SendMessageResponse();
            r.setServerMsgId(exist.getServerMsgId());
            r.setStatus("SAVED");
            r.setTs(Instant.now().toEpochMilli());
            return r;
        }

        long msgId = IdWorker.getId();
        String serverMsgId = String.valueOf(msgId);
        ClientMsgIdIdempotency.Claim claim = new ClientMsgIdIdempotency.Claim();
        claim.setServerMsgId(serverMsgId);
        if (idempotency.putIfAbsent(key, claim) != null) {
            SendMessageResponse r = new SendMessageResponse();
            r.setServerMsgId(serverMsgId);
            r.setStatus("SAVED");
            r.setTs(Instant.now().toEpochMilli());
            return r;
        }

        MessageEntity m = new MessageEntity();
        m.setId(msgId);
        m.setServerMsgId(serverMsgId);
        m.setChatType(ChatType.SINGLE);
        m.setSingleChatId(singleChatId);
        m.setFromUserId(fromUserId);
        m.setToUserId(toUserId);
        m.setMsgType(MessageType.TEXT);
        m.setContent(content);
        m.setClientMsgId(clientMsgId);
        m.setStatus(MessageStatus.SAVED);
        messageService.save(m);

        // 可选：如果对端在线，经 WS 推送
        var ch = sessionRegistry.getChannel(toUserId);
        if (ch != null && ch.isActive()) {
            try {
                WsEnvelope env = new WsEnvelope();
                env.type = "SINGLE_CHAT";
                env.from = fromUserId;
                env.to = toUserId;
                env.clientMsgId = clientMsgId;
                env.serverMsgId = serverMsgId;
                env.msgType = MessageType.TEXT.name();
                env.body = content;
                env.ts = Instant.now().toEpochMilli();
                String json = objectMapper.writeValueAsString(env);
                ch.writeAndFlush(new io.netty.handler.codec.http.websocketx.TextWebSocketFrame(json));
                // 更新状态为 DELIVERED（异步）
                CompletableFuture.runAsync(() -> {
                    MessageEntity patch = new MessageEntity();
                    BeanUtil.copyProperties(m, patch);
                    patch.setStatus(MessageStatus.DELIVERED);
                    messageService.updateById(patch);
                });
            } catch (Exception ignored) {}
        }

        SendMessageResponse r = new SendMessageResponse();
        r.setServerMsgId(serverMsgId);
        r.setStatus("SAVED");
        r.setTs(Instant.now().toEpochMilli());
        return r;
    }

    @Override
    public HistoryResponse history(Long selfUserId, Long peerId, Long cursor, Integer size) {
        Long u1 = Math.min(selfUserId, peerId);
        Long u2 = Math.max(selfUserId, peerId);
        Long singleChatId = singleChatService.getOrCreateSingleChatId(u1, u2);
        if (size == null || size <= 0) size = 20;
        if (size > 200) size = 200;
        LambdaQueryWrapper<MessageEntity> qw = new LambdaQueryWrapper<>();
        qw.eq(MessageEntity::getChatType, ChatType.SINGLE)
          .eq(MessageEntity::getSingleChatId, singleChatId);
        if (cursor != null && cursor > 0) {
            qw.lt(MessageEntity::getId, cursor);
        }
        qw.orderByDesc(MessageEntity::getId).last("LIMIT " + size);
        List<MessageEntity> list = messageService.list(qw);
        Long next = (list.size() == size) ? list.get(list.size()-1).getId() : null;
        boolean hasMore = (next != null);
        HistoryResponse hr = new HistoryResponse();
        hr.setItems(list.stream().map(e -> {
            MessageDTO d = new MessageDTO();
            d.setId(e.getId());
            d.setFromUserId(e.getFromUserId());
            d.setToUserId(e.getToUserId());
            d.setMsgType(e.getMsgType() == null ? MessageType.TEXT.name() : e.getMsgType().name());
            d.setContent(e.getContent());
            d.setTs(e.getCreatedAt() == null ? null : e.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
            return d;
        }).collect(Collectors.toList()));
        hr.setNextCursor(next);
        hr.setHasMore(hasMore);
        return hr;
    }

    @Override
    public Long getOrCreateSingleChatId(Long user1Id, Long user2Id) {
        Long u1 = Math.min(user1Id, user2Id);
        Long u2 = Math.max(user1Id, user2Id);
        return singleChatService.getOrCreateSingleChatId(u1, u2);
    }
}
