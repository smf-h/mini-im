package com.miniim.gateway.ws;

import com.miniim.auth.service.SessionVersionStore;
import com.miniim.gateway.session.SessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
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
    private final SessionVersionStore sessionVersionStore;
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
        if (!isSessionValid(ch)) {
            wsWriter.writeError(ctx, "session_invalid", null, null);
            sessionRegistry.unbind(ch);
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
            if (!isSessionValid(ch)) {
                wsWriter.writeError(ch, "session_invalid", null, null);
                sessionRegistry.unbind(ch);
                ctx.close();
                return;
            }
            sessionRegistry.touch(ch);
        }
        // WS 层 ping：触发客户端自动回 pong，用于：
        // 1) NAT/中间设备保活（避免长连接被静默回收）
        // 2) 配合 reader-idle：让“正常在线”连接持续有读事件；僵尸连接则会触发 READER_IDLE 清理
        try {
            ctx.writeAndFlush(new PingWebSocketFrame());
        } catch (Exception ignore) {
            // ignore
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

    private boolean isSessionValid(Channel ch) {
        Long userId = ch.attr(SessionRegistry.ATTR_USER_ID).get();
        Long sv = ch.attr(SessionRegistry.ATTR_SESSION_VERSION).get();
        if (userId == null || sv == null) {
            return true;
        }
        return sessionVersionStore.isValid(userId, sv);
    }
}
