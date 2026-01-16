package com.miniim.gateway.ws;

import com.miniim.gateway.config.WsBackpressureProperties;
import com.miniim.gateway.session.SessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class WsBackpressureHandler extends ChannelInboundHandlerAdapter {

    private static final AttributeKey<Long> ATTR_UNWRITABLE_SINCE_MS =
            AttributeKey.valueOf("im:ws:bp:unwritable_since_ms");
    private static final AttributeKey<ScheduledFuture<?>> ATTR_UNWRITABLE_CLOSE_FUTURE =
            AttributeKey.valueOf("im:ws:bp:unwritable_close_future");

    private final WsBackpressureProperties props;

    public WsBackpressureHandler(WsBackpressureProperties props) {
        this.props = props;
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (props == null || !props.enabledEffective()) {
            ctx.fireChannelWritabilityChanged();
            return;
        }

        Channel ch = ctx.channel();
        if (ch == null) {
            ctx.fireChannelWritabilityChanged();
            return;
        }

        if (ch.isWritable()) {
            clearUnwritableState(ch);
            ctx.fireChannelWritabilityChanged();
            return;
        }

        markUnwritableIfAbsent(ch);
        scheduleCloseIfNeeded(ch);
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Channel ch = ctx.channel();
        if (ch != null) {
            clearUnwritableState(ch);
        }
        super.channelInactive(ctx);
    }

    private void markUnwritableIfAbsent(Channel ch) {
        Long existing = ch.attr(ATTR_UNWRITABLE_SINCE_MS).get();
        if (existing != null && existing > 0) {
            return;
        }
        ch.attr(ATTR_UNWRITABLE_SINCE_MS).set(System.currentTimeMillis());
    }

    private void clearUnwritableState(Channel ch) {
        ch.attr(ATTR_UNWRITABLE_SINCE_MS).set(null);
        ScheduledFuture<?> f = ch.attr(ATTR_UNWRITABLE_CLOSE_FUTURE).getAndSet(null);
        if (f != null) {
            f.cancel(false);
        }
    }

    private void scheduleCloseIfNeeded(Channel ch) {
        long closeAfterMs = props.closeUnwritableAfterMsEffective();
        if (closeAfterMs < 0) {
            return;
        }
        ScheduledFuture<?> existing = ch.attr(ATTR_UNWRITABLE_CLOSE_FUTURE).get();
        if (existing != null) {
            return;
        }

        ScheduledFuture<?> future = ch.eventLoop().schedule(() -> {
            try {
                if (!ch.isActive()) {
                    return;
                }
                if (ch.isWritable()) {
                    clearUnwritableState(ch);
                    return;
                }
                Long since = ch.attr(ATTR_UNWRITABLE_SINCE_MS).get();
                long durMs = since == null ? -1 : (System.currentTimeMillis() - since);

                Long uid = ch.attr(SessionRegistry.ATTR_USER_ID).get();
                String cid = ch.attr(SessionRegistry.ATTR_CONN_ID).get();

                log.warn("ws backpressure: closing slow consumer channel: uid={}, cid={}, durMs={}, bytesBeforeUnwritable={}, bytesBeforeWritable={}",
                        uid, cid, durMs, ch.bytesBeforeUnwritable(), ch.bytesBeforeWritable());
                ch.close();
            } catch (Exception e) {
                log.debug("ws backpressure close failed: {}", e.toString());
            }
        }, closeAfterMs, TimeUnit.MILLISECONDS);

        ch.attr(ATTR_UNWRITABLE_CLOSE_FUTURE).set(future);
    }
}

