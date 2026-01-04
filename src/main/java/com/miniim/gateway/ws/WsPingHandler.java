package com.miniim.gateway.ws;

import com.miniim.gateway.session.SessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * WS 心跳处理：
 * - 客户端 PING -> 服务端 PONG（并 touch 在线 TTL）
 * - 服务端 WRITER_IDLE -> 发出 JSON PING（并 touch 在线 TTL，避免仅靠客户端心跳）
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WsPingHandler {

    private final SessionRegistry sessionRegistry;
    private final WsWriter wsWriter;

    public void handleClientPing(ChannelHandlerContext ctx) {
        Channel ch = ctx.channel();
        if (!sessionRegistry.isAuthed(ch)) {
            wsWriter.writeError(ctx, "unauthorized", null, null);
            ctx.close();
            return;
        }
        if (isExpired(ch)) {
            wsWriter.writeError(ctx, "token_expired", null, null);
            ctx.close();
            return;
        }
        sessionRegistry.touch(ch);

        WsEnvelope pong = new WsEnvelope();
        pong.type = "PONG";
        pong.ts = Instant.now().toEpochMilli();
        wsWriter.write(ctx, pong);
    }

    public void onWriterIdle(ChannelHandlerContext ctx) {
        Channel ch = ctx.channel();
        if (sessionRegistry.isAuthed(ch) && !isExpired(ch)) {
            sessionRegistry.touch(ch);
        }
        WsEnvelope ping = new WsEnvelope();
        ping.type = "PING";
        ping.ts = Instant.now().toEpochMilli();
        wsWriter.write(ctx, ping);
    }

    private boolean isExpired(Channel ch) {
        Long expMs = sessionRegistry.getAccessExpMs(ch);
        return expMs != null && Instant.now().toEpochMilli() >= expMs;
    }
}

