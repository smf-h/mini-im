package com.miniim.gateway.ws;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.miniim.auth.service.JwtService;
import com.miniim.domain.entity.MessageEntity;
import com.miniim.domain.enums.AckType;
import com.miniim.domain.enums.ChatType;
import com.miniim.domain.enums.MessageStatus;
import com.miniim.domain.enums.MessageType;
import com.miniim.domain.service.ConversationService;
import com.miniim.domain.service.MessageService;
import com.miniim.domain.service.SingleChatService;
import com.miniim.domain.service.UserService;
import com.miniim.gateway.session.SessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Slf4j
public class WsFrameHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final int MAX_BODY_LEN = 4096;

    private final ObjectMapper objectMapper;
    private final JwtService jwtService;
    private final SessionRegistry sessionRegistry;
    private final MessageService messageService;
    private final ConversationService conversationService;
    private  final SingleChatService singleChatService;
    private final Executor dbExecutor;
    private final ClientMsgIdIdempotency idempotency;
    private final UserService userService;
    public WsFrameHandler(ObjectMapper objectMapper,
                          JwtService jwtService,
                          SessionRegistry sessionRegistry,
                          MessageService messageService,
                          ConversationService conversationService,
                          SingleChatService singleChatService,
                          Executor dbExecutor, ClientMsgIdIdempotency idempotency, UserService userService) {
        this.objectMapper = objectMapper;
        this.jwtService = jwtService;
        this.sessionRegistry = sessionRegistry;
        this.messageService = messageService;
        this.conversationService = conversationService;
        this.singleChatService = singleChatService;
        this.dbExecutor = dbExecutor;
        this.idempotency = idempotency;
        this.userService = userService;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) throws Exception {
        // 我们约定：客户端发来的每一个 TextWebSocketFrame 都是一段 JSON
        // 用 WsEnvelope 这个“信封对象”承载：type + token + from/to + body + ts ...
        log.info("received frame {}", frame.text());
        WsEnvelope msg;
        try {
            msg = objectMapper.readValue(frame.text(), WsEnvelope.class);
            log.info("received frame {}", msg.toString());
        } catch (Exception e) {
            writeError(ctx, "bad_json");
            return;
        }

        if (msg.type == null) {
            writeError(ctx, "missing_type");
            return;
        }

        // 除了 AUTH（兼容旧客户端）以外，其他消息都要求：已鉴权且 accessToken 未过期。
        if (!"AUTH".equals(msg.type)) {
            if (!sessionRegistry.isAuthed(ctx.channel())) {
                writeError(ctx, "unauthorized");
                ctx.close();
                return;
            }
            if (isExpired(ctx)) {
                writeError(ctx, "token_expired");
                ctx.close();
                return;
            }
        }

        switch (msg.type) {
            case "AUTH" -> handleAuth(ctx, msg);
            case "PING" -> handlePing(ctx);
            case "SINGLE_CHAT" -> handleSingleChat(ctx, msg);
            case "GROUP_CHAT" -> handleGroupChat(ctx, msg);
            case "ACK" -> handleAck(ctx, msg);
            default -> {
                writeError(ctx, "not_implemented", msg.getClientMsgId(), null);
            }
        }
    }

    private void handleSingleChat(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel channel = ctx.channel();
        Long fromUserId = channel.attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId == null) {
             writeError(ctx, "unauthorized", msg.getClientMsgId(), null);
             return;
        }
        if (!validateSingleChat(ctx, msg)) {
            writeError(ctx, "invalid_single_chat", msg.getClientMsgId(), null);
            return;
        }
        Long toUserId = msg.getTo();
        if (toUserId.equals(fromUserId)) {
             writeError(ctx, "cannot_send_to_self", msg.getClientMsgId(), null);
             return;
        }
        Channel channelTo = sessionRegistry.getChannel(toUserId);
//        if (channelTo == null) {
//             writeError(ctx, "channel_not_found", msg.getClientMsgId(), null);
//        }
//        if (channelTo!=null&&!channelTo.isActive()) {
//             writeError(ctx, "target_channel_inactive", msg.getClientMsgId(), null);
//        }
        Boolean dropped = channelTo==null || !channelTo.isActive();
        // 使用 Hutool：先拷贝出新对象，再修改新对象（避免多线程/异步回调里修改入参 msg）。
        final WsEnvelope base = BeanUtil.toBean(msg, WsEnvelope.class);
        base.setFrom(fromUserId);
        base.setTs(Instant.now().toEpochMilli());

        Long user1Id = Math.min(fromUserId, toUserId);
        Long user2Id = Math.max(fromUserId, toUserId);
        Long messageId = IdWorker.getId();
        String serverMsgId = String.valueOf(messageId);
        String key=idempotency.key(fromUserId.toString(),msg.getClientMsgId());
        ClientMsgIdIdempotency.Claim newClaim = new ClientMsgIdIdempotency.Claim();
        newClaim.setServerMsgId(serverMsgId);
        ClientMsgIdIdempotency.Claim claim = idempotency.putIfAbsent(key,newClaim);
        if (claim!=null) {
            writeAck(ctx,fromUserId, msg.getClientMsgId(),claim.getServerMsgId(), AckType.SAVED.getDesc());
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
        messageEntity.setStatus(dropped ? MessageStatus.DROPPED : MessageStatus.SAVED);
        messageEntity.setContent(msg.getBody());
        messageEntity.setClientMsgId(msg.getClientMsgId());
        base.setServerMsgId(serverMsgId);
        // 注意：这里的 save 是阻塞式 DB 操作；生产建议放到业务线程池执行，避免阻塞 Netty eventLoop。
        CompletableFuture<Long> saveFuture = CompletableFuture.supplyAsync(() ->{
            Long singleChatId =singleChatService.getOrCreateSingleChatId(user1Id, user2Id);
            messageEntity.setSingleChatId(singleChatId);
            messageService.save(messageEntity);
            return messageEntity.getId();
            }, dbExecutor).orTimeout(3,TimeUnit.SECONDS);
        saveFuture.whenComplete((result,error)->{
            if(error!=null){
                log.error("save message failed: {}", error.toString());
                idempotency.remove(key);
               ctx.executor().execute(() -> writeError(ctx, "internal_error", msg.getClientMsgId(), serverMsgId));
                return;
            }
            else{
            ctx.executor().execute(() -> writeAck(ctx, fromUserId, base.getClientMsgId(), serverMsgId, AckType.SAVED.getDesc()));
            }
            if (dropped) {
                return;
            }

            channelTo.eventLoop().execute(()->{
                ChannelFuture future;
                try {
                    WsEnvelope out = BeanUtil.toBean(base, WsEnvelope.class);
                    // 明确下发类型（防止客户端乱填/或未来扩展复用 envelope）
                    out.setType("SINGLE_CHAT");
                    future = write(channelTo, out);
            } catch (Exception e) {
                    ctx.executor().execute(() -> writeError(ctx, "internal_error", base.getClientMsgId(), serverMsgId));
                    return;
                }
                future.addListener(f -> {
                    if (f.isSuccess()) {
                        // 更新消息状态为 DELIVERED
                        CompletableFuture.runAsync(() -> {
                            MessageEntity newMessageEntity = new MessageEntity();
                            BeanUtil.copyProperties(messageEntity, newMessageEntity);
                            newMessageEntity.setStatus(MessageStatus.DELIVERED);
                            messageService.updateById(newMessageEntity);
                        }, dbExecutor).orTimeout(3,TimeUnit.SECONDS);
                        ctx.executor().execute(() -> writeAck(ctx, fromUserId, base.getClientMsgId(), serverMsgId, "DELIVERED"));
                    } else {
                        log.error("deliver message to user {} failed: {}", toUserId, f.cause().toString());
                        ctx.executor().execute(() -> writeError(ctx, "deliver_failed", base.getClientMsgId(), serverMsgId));

                    }
                });
            });
        });
    }

    private void handleGroupChat(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel channel = ctx.channel();
        Long fromUserId = channel.attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId == null) {
            writeError(ctx, "unauthorized", msg.getClientMsgId(), msg.getServerMsgId());
            ctx.close();
            return;
        }

        if (!validateGroupChat(ctx, msg)) {
            return;
        }

        msg.setFrom(fromUserId);
        msg.setTs(Instant.now().toEpochMilli());

        int delivered = 0;
        for (Channel ch : sessionRegistry.getAllChannels()) {
            if (ch == null || ch == channel) {
                continue;
            }
            if (!ch.isActive()) {
                continue;
            }
            write(ch, msg);
            delivered++;
        }

        WsEnvelope ack = new WsEnvelope();
        ack.type = "ACK";
        ack.from = fromUserId;
        ack.clientMsgId = msg.getClientMsgId();
        ack.ackType = "DELIVERED";
        ack.body = "delivered=" + delivered;
        ack.ts = Instant.now().toEpochMilli();
        write(ctx, ack);
    }

    private void handleAck(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel channel = ctx.channel();
        Long fromUserId = channel.attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId == null) {
            writeError(ctx, "unauthorized", msg.getClientMsgId(), msg.getServerMsgId());
            ctx.close();
            return ;
        }

        if (!validateAck(ctx, msg)) {
            return ;
        }

        msg.setFrom(fromUserId);
        msg.setTs(Instant.now().toEpochMilli());

        Long toUserId = msg.getTo();
        Channel target = sessionRegistry.getChannel(toUserId);
//        if (target == null || !target.isActive()) {
//            writeError(ctx, "ack_target_offline", msg.getClientMsgId(), msg.getServerMsgId());
//            return false;
//        }

        if ("received".equalsIgnoreCase(msg.ackType) || "ack_receive".equalsIgnoreCase(msg.ackType)) {
             handleAckReceived(msg, fromUserId, toUserId);
        }

        // 未处理其他类型
        return ;
    }

    private void handleAckReceived(WsEnvelope msg, Long fromUserId, Long toUserId) {
        LambdaUpdateWrapper<MessageEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(MessageEntity::getClientMsgId, msg.getClientMsgId());
        updateWrapper.eq(MessageEntity::getServerMsgId, msg.getServerMsgId());
        updateWrapper.eq(MessageEntity::getFromUserId, toUserId);
        updateWrapper.eq(MessageEntity::getToUserId, fromUserId);
        updateWrapper.set(MessageEntity::getStatus, MessageStatus.RECEIVED);
        Boolean result;
        CompletableFuture<Boolean> completableFuture = CompletableFuture.supplyAsync(() -> messageService.update(updateWrapper), dbExecutor).orTimeout(3, TimeUnit.SECONDS)
                .exceptionally(e -> {
                    log.error("update message failed: {}", e.toString());
                    return null;
                });

    }

    private void handleAuth(ChannelHandlerContext ctx, WsEnvelope msg) {
        // 为什么不在 WS 握手（HTTP Upgrade）阶段就鉴权？
        // 2) 首包 AUTH 是 IM 场景很常见的做法：协议统一、实现简单、也方便后续做重连补偿
        // 3) 面试时也更好讲清楚：连接建立 != 已鉴权，必须 AUTH 才能发业务消息
        // 兼容旧客户端：如果已经在握手阶段鉴权过，直接返回 AUTH_OK。
        if (sessionRegistry.isAuthed(ctx.channel())) {
            Long uid = ctx.channel().attr(SessionRegistry.ATTR_USER_ID).get();
            writeAuthOk(ctx, uid == null ? -1 : uid);
            if (uid != null) {
                resendDroppedMessages(ctx, uid);
            }
            return;
        }

        if (msg.token == null || msg.token.isBlank()) {
            writeAuthFail(ctx, "missing_token");
            ctx.close();
            return;
        }
        Long userId;
        try {
            Jws<Claims> jws = jwtService.parseAccessToken(msg.token);
             userId = jwtService.getUserId(jws.getPayload());
            Long expMs = jws.getPayload().getExpiration() == null ? null : jws.getPayload().getExpiration().getTime();

            sessionRegistry.bind(ctx.channel(), userId, expMs);
            writeAuthOk(ctx, userId);
        } catch (Exception e) {
            writeAuthFail(ctx, "invalid_token");
            ctx.close();
            return;
        }

        resendDroppedMessages(ctx, userId);




    }

    private void resendDroppedMessages(ChannelHandlerContext ctx, Long userId) {
        LambdaQueryWrapper<MessageEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(MessageEntity::getStatus, MessageStatus.DROPPED)
                .eq(MessageEntity::getToUserId, userId)
                .orderByAsc(MessageEntity::getId)
                .last("limit 100");

        CompletableFuture.runAsync(() -> {
            List<MessageEntity> list = messageService.list(queryWrapper);
            Channel target = ctx.channel();
            if (target == null || !target.isActive()) {
                return;
            }
            for (MessageEntity msgEntity : list) {
                msgEntity.setStatus(MessageStatus.SAVED);
                messageService.updateById(msgEntity);

                WsEnvelope envelope = new WsEnvelope();
                envelope.setType(msgEntity.getChatType().getDesc());
                envelope.setFrom(msgEntity.getFromUserId());
                envelope.setTs(Instant.now().toEpochMilli());
                envelope.setClientMsgId(msgEntity.getClientMsgId());
                envelope.setTo(userId);
                envelope.setServerMsgId(msgEntity.getServerMsgId());
                envelope.setBody(msgEntity.getContent());
                envelope.setMsgType(msgEntity.getMsgType().getDesc());

                target.eventLoop().execute(() -> {
                    ChannelFuture write = write(target, envelope);
                    write.addListener(w -> {
                        if (!w.isSuccess()) {
                            log.error("write error:{}", w.cause().toString());
                        }
                    });
                });
            }
        }, dbExecutor).orTimeout(3, TimeUnit.SECONDS).exceptionally(e -> {
            log.error("resend dropped messages failed: {}", e.toString());
            return null;
        });
    }

    private ChannelFuture handlePing(ChannelHandlerContext ctx) {
        if (!sessionRegistry.isAuthed(ctx.channel())) {
            ChannelFuture f = writeError(ctx, "unauthorized", null, null);
            ctx.close();
            return f;
        }
        if (isExpired(ctx)) {
            ChannelFuture f = writeError(ctx, "token_expired", null, null);
            ctx.close();
            return f;
        }
        sessionRegistry.touch(ctx.channel());
        WsEnvelope pong = new WsEnvelope();
        pong.type = "PONG";
        pong.ts = Instant.now().toEpochMilli();
        return write(ctx, pong);
    }
    private void Ping(ChannelHandlerContext ctx){
        WsEnvelope ping = new WsEnvelope();
        ping.type = "PING";
        ping.ts = Instant.now().toEpochMilli();
        write(ctx, ping);
    }

    private boolean isExpired(ChannelHandlerContext ctx) {
        Long expMs = sessionRegistry.getAccessExpMs(ctx.channel());
        return expMs != null && Instant.now().toEpochMilli() >= expMs;
    }

    private ChannelFuture writeAuthOk(ChannelHandlerContext ctx, long userId) {
        WsEnvelope ok = new WsEnvelope();
        ok.type = "AUTH_OK";
        ok.from = userId;
        ok.ts = Instant.now().toEpochMilli();
        return write(ctx, ok);
    }

    private ChannelFuture writeAuthFail(ChannelHandlerContext ctx, String reason) {
        WsEnvelope fail = new WsEnvelope();
        fail.type = "AUTH_FAIL";
        fail.reason = reason;
        fail.ts = Instant.now().toEpochMilli();
        return write(ctx, fail);
    }

    private ChannelFuture writeError(ChannelHandlerContext ctx, String reason) {
        return writeError(ctx, reason, null, null);
    }

    private ChannelFuture writeError(ChannelHandlerContext ctx, String reason, String msgId, String serverMsgId) {
        WsEnvelope err = new WsEnvelope();
        err.type = "ERROR";
        err.clientMsgId = msgId;
        err.serverMsgId = serverMsgId;
        err.reason = reason;
        err.ts = Instant.now().toEpochMilli();
        return write(ctx, err);
    }

    private ChannelFuture writeAck(ChannelHandlerContext ctx, long fromUserId, String msgId, String serverMsgId, String ackType) {
        WsEnvelope ack = new WsEnvelope();
        ack.type = "ACK";
        ack.from = fromUserId;
        ack.clientMsgId = msgId;
        ack.serverMsgId = serverMsgId;
        ack.ackType = ackType;
        ack.ts = Instant.now().toEpochMilli();
        return write(ctx, ack);
    }

    private boolean validateSingleChat(ChannelHandlerContext ctx, WsEnvelope msg) {
        if (msg.getClientMsgId() == null || msg.getClientMsgId().isBlank()) {
            writeError(ctx, "missing_msg_id", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getTo() == null) {
            writeError(ctx, "missing_to", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getBody() == null || msg.getBody().isBlank()) {
            writeError(ctx, "missing_body", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getBody().length() > MAX_BODY_LEN) {
            writeError(ctx, "body_too_long", msg.getClientMsgId(), null);
            return false;
        }
        return true;
    }

    private boolean validateGroupChat(ChannelHandlerContext ctx, WsEnvelope msg) {
        if (msg.getClientMsgId() == null || msg.getClientMsgId().isBlank()) {
            writeError(ctx, "missing_msg_id", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getGroupId() == null || msg.getGroupId() <= 0) {
            writeError(ctx, "missing_group_id", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getBody() == null || msg.getBody().isBlank()) {
            writeError(ctx, "missing_body", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getBody().length() > MAX_BODY_LEN) {
            writeError(ctx, "body_too_long", msg.getClientMsgId(), null);
            return false;
        }
        return true;
    }

    private boolean validateAck(ChannelHandlerContext ctx, WsEnvelope msg) {
        if (msg.getClientMsgId() == null || msg.getClientMsgId().isBlank()) {
            writeError(ctx, "missing_msg_id", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getTo() == null) {
            writeError(ctx, "missing_to", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getAckType() == null || msg.getAckType().isBlank()) {
            writeError(ctx, "missing_ack_type", msg.getClientMsgId(), null);
            return false;
        }
        return true;
    }

    private ChannelFuture write(ChannelHandlerContext ctx, WsEnvelope env) {
        try {
            String json = objectMapper.writeValueAsString(env);
            return ctx.writeAndFlush(new TextWebSocketFrame(json));
        } catch (Exception e) {
            log.warn("serialize ws message failed: {}", e.toString());
            return ctx.channel().newFailedFuture(e);
        }
    }

    private ChannelFuture write(Channel ch, WsEnvelope env) {
        String json;
        try {
            json = objectMapper.writeValueAsString(env);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return ch.writeAndFlush(new TextWebSocketFrame(json));
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent e && e.state() == IdleState.READER_IDLE) {
            // READER_IDLE：一段时间没有收到任何数据。
            // 这通常意味着：
            // - 客户端断网了但 TCP 没及时感知
            // - 客户端异常退出
            // - 客户端没按约定发心跳
            // 我们的处理策略：解绑会话并关闭连接。
            sessionRegistry.unbind(ctx.channel());
            ctx.close();
        }
        if (evt instanceof IdleStateEvent e && e.state() == IdleState.WRITER_IDLE) {
            Ping(ctx);
            //再次检测
//               sessionRegistry.unbind(ctx.channel());
//                ctx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {

        sessionRegistry.unbind(ctx.channel());
    }
    public void channelActive(ChannelHandlerContext ctx) {
        log.info(ctx.channel() + " connected");

    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        sessionRegistry.unbind(ctx.channel());
        ctx.close();
    }
}


