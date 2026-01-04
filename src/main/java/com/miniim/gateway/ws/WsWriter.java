package com.miniim.gateway.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * WS 文本协议统一写出器：
 * - 统一序列化/错误回包/ACK 回包
 * - 保证 ch.writeAndFlush 在对应 channel eventLoop 执行
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WsWriter {

    private final ObjectMapper objectMapper;

    public ChannelFuture write(ChannelHandlerContext ctx, WsEnvelope env) {
        if (ctx == null) {
            throw new IllegalArgumentException("ctx is null");
        }
        Channel ch = ctx.channel();
        if (ch == null) {
            throw new IllegalArgumentException("ctx.channel is null");
        }
        if (ch.eventLoop().inEventLoop()) {
            return doWrite(ctx, env);
        }
        ChannelPromise promise = ch.newPromise();
        ch.eventLoop().execute(() -> doWrite(ctx, env).addListener(f -> {
            if (f.isSuccess()) {
                promise.setSuccess();
            } else {
                promise.setFailure(f.cause());
            }
        }));
        return promise;
    }

    public ChannelFuture write(Channel ch, WsEnvelope env) {
        if (ch == null) {
            throw new IllegalArgumentException("channel is null");
        }
        if (ch.eventLoop().inEventLoop()) {
            return doWrite(ch, env);
        }
        ChannelPromise promise = ch.newPromise();
        ch.eventLoop().execute(() -> doWrite(ch, env).addListener(f -> {
            if (f.isSuccess()) {
                promise.setSuccess();
            } else {
                promise.setFailure(f.cause());
            }
        }));
        return promise;
    }

    public ChannelFuture writeError(ChannelHandlerContext ctx, String reason, String clientMsgId, String serverMsgId) {
        WsEnvelope err = new WsEnvelope();
        err.type = "ERROR";
        err.clientMsgId = clientMsgId;
        err.serverMsgId = serverMsgId;
        err.reason = reason;
        err.ts = Instant.now().toEpochMilli();
        return write(ctx, err);
    }

    public ChannelFuture writeAck(ChannelHandlerContext ctx, long fromUserId, String clientMsgId, String serverMsgId, String ackType, String body) {
        WsEnvelope ack = new WsEnvelope();
        ack.type = "ACK";
        ack.from = fromUserId;
        ack.clientMsgId = clientMsgId;
        ack.serverMsgId = serverMsgId;
        ack.ackType = ackType;
        ack.body = body;
        ack.ts = Instant.now().toEpochMilli();
        return write(ctx, ack);
    }

    private ChannelFuture doWrite(ChannelHandlerContext ctx, WsEnvelope env) {
        try {
            String json = objectMapper.writeValueAsString(env);
            return ctx.writeAndFlush(new TextWebSocketFrame(json));
        } catch (Exception e) {
            log.warn("ws serialize/write failed: {}", e.toString());
            return ctx.channel().newFailedFuture(e);
        }
    }

    private ChannelFuture doWrite(Channel ch, WsEnvelope env) {
        try {
            String json = objectMapper.writeValueAsString(env);
            return ch.writeAndFlush(new TextWebSocketFrame(json));
        } catch (Exception e) {
            log.warn("ws serialize/write failed: {}", e.toString());
            return ch.newFailedFuture(e);
        }
    }
}

