package com.miniim.gateway.ws;

import com.miniim.gateway.session.SessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.concurrent.TimeUnit;

/**
 * AUTH-first：握手完成后要求客户端在固定窗口内完成 AUTH，否则先回 ERROR 再断连。
 */
public class WsAuthTimeoutHandler extends ChannelInboundHandlerAdapter {

    private static final AttributeKey<ScheduledFuture<?>> ATTR_AUTH_TIMEOUT =
            AttributeKey.valueOf("im:ws:auth_timeout_future");

    private final SessionRegistry sessionRegistry;
    private final WsWriter wsWriter;
    private final long timeoutMs;

    public WsAuthTimeoutHandler(SessionRegistry sessionRegistry, WsWriter wsWriter, long timeoutMs) {
        this.sessionRegistry = sessionRegistry;
        this.wsWriter = wsWriter;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt == WebSocketServerProtocolHandler.ServerHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            scheduleIfNeeded(ctx);
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancel(ctx.channel());
        super.channelInactive(ctx);
    }

    private void scheduleIfNeeded(ChannelHandlerContext ctx) {
        Channel ch = ctx.channel();
        if (ch == null || timeoutMs <= 0) {
            return;
        }
        if (sessionRegistry.isAuthed(ch)) {
            return;
        }
        ScheduledFuture<?> existing = ch.attr(ATTR_AUTH_TIMEOUT).get();
        if (existing != null && !existing.isDone()) {
            return;
        }
        ScheduledFuture<?> f = ctx.executor().schedule(() -> onTimeout(ctx), timeoutMs, TimeUnit.MILLISECONDS);
        ch.attr(ATTR_AUTH_TIMEOUT).set(f);
    }

    private void onTimeout(ChannelHandlerContext ctx) {
        Channel ch = ctx.channel();
        if (ch == null || !ch.isActive()) {
            return;
        }
        if (sessionRegistry.isAuthed(ch)) {
            return;
        }
        ChannelFuture wf = wsWriter.writeError(ctx, "auth_timeout", null, null);
        wf.addListener(ignored -> {
            try {
                ctx.close();
            } catch (Exception ignore) {
            }
        });
    }

    private void cancel(Channel ch) {
        if (ch == null) {
            return;
        }
        ScheduledFuture<?> f = ch.attr(ATTR_AUTH_TIMEOUT).getAndSet(null);
        if (f != null) {
            try {
                f.cancel(false);
            } catch (Exception ignore) {
            }
        }
    }
}

