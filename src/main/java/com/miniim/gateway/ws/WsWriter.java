package com.miniim.gateway.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniim.gateway.config.WsBackpressureProperties;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;

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
    private final WsBackpressureProperties backpressureProps;

    public static final class PreparedBytes implements AutoCloseable {
        private final ByteBuf buf;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private PreparedBytes(ByteBuf buf) {
            this.buf = Objects.requireNonNull(buf, "buf");
        }

        ByteBuf retainedDuplicate() {
            return buf.retainedDuplicate();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    buf.release();
                } catch (Exception ignore) {
                }
            }
        }
    }

    public PreparedBytes prepareBytes(WsEnvelope env) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(env);
            return new PreparedBytes(Unpooled.wrappedBuffer(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("ws prepare bytes failed", e);
        }
    }

    public ChannelFuture write(ChannelHandlerContext ctx, WsEnvelope env) {
        if (ctx == null) {
            throw new IllegalArgumentException("ctx is null");
        }
        Channel ch = ctx.channel();
        if (ch == null) {
            throw new IllegalArgumentException("ctx.channel is null");
        }
        return write(ch, env, null);
    }

    public ChannelFuture write(Channel ch, WsEnvelope env) {
        return write(ch, env, null);
    }

    public ChannelFuture write(Channel ch, WsEnvelope env, LongConsumer eventLoopDelayNsConsumer) {
        if (ch == null) {
            throw new IllegalArgumentException("channel is null");
        }
        ChannelFuture fastFail = failFastIfUnwritable(ch);
        if (fastFail != null) {
            safeAcceptDelayConsumer(eventLoopDelayNsConsumer, 0L);
            return fastFail;
        }
        if (ch.eventLoop().inEventLoop()) {
            safeAcceptDelayConsumer(eventLoopDelayNsConsumer, 0L);
            return doWrite(ch, env);
        }
        ChannelPromise promise = ch.newPromise();
        long enqueuedAtNs = System.nanoTime();
        try {
            ch.eventLoop().execute(() -> {
                safeAcceptDelayConsumer(eventLoopDelayNsConsumer, System.nanoTime() - enqueuedAtNs);
                doWrite(ch, env).addListener(f -> {
                    if (f.isSuccess()) {
                        promise.setSuccess();
                    } else {
                        promise.setFailure(f.cause());
                    }
                });
            });
        } catch (Exception e) {
            safeAcceptDelayConsumer(eventLoopDelayNsConsumer, 0L);
            promise.setFailure(e);
        }
        return promise;
    }

    public ChannelFuture writePreparedBytes(Channel ch, WsEnvelope envMeta, PreparedBytes prepared) {
        if (ch == null) {
            throw new IllegalArgumentException("channel is null");
        }
        if (prepared == null) {
            throw new IllegalArgumentException("prepared is null");
        }
        ChannelFuture fastFail = failFastIfUnwritable(ch);
        if (fastFail != null) {
            return fastFail;
        }

        if (backpressureProps != null
                && backpressureProps.enabledEffective()
                && backpressureProps.dropWhenUnwritableEffective()
                && !ch.isWritable()) {
            return ch.newFailedFuture(new IllegalStateException("ws backpressure: channel not writable"));
        }

        ByteBuf dup = prepared.retainedDuplicate();
        if (ch.eventLoop().inEventLoop()) {
            try {
                return ch.writeAndFlush(new TextWebSocketFrame(dup));
            } catch (Exception e) {
                try {
                    dup.release();
                } catch (Exception ignore) {
                }
                return ch.newFailedFuture(e);
            }
        }
        ChannelPromise promise = ch.newPromise();
        try {
            ch.eventLoop().execute(() -> ch.writeAndFlush(new TextWebSocketFrame(dup)).addListener(f -> {
                if (f.isSuccess()) {
                    promise.setSuccess();
                } else {
                    promise.setFailure(f.cause());
                }
            }));
        } catch (Exception e) {
            try {
                dup.release();
            } catch (Exception ignore) {
            }
            promise.setFailure(e);
        }
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

    public ChannelFuture writeError(Channel ch, String reason, String clientMsgId, String serverMsgId) {
        WsEnvelope err = new WsEnvelope();
        err.type = "ERROR";
        err.clientMsgId = clientMsgId;
        err.serverMsgId = serverMsgId;
        err.reason = reason;
        err.ts = Instant.now().toEpochMilli();
        return write(ch, err);
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

    public ChannelFuture writeAck(ChannelHandlerContext ctx,
                                 long fromUserId,
                                 String clientMsgId,
                                 String serverMsgId,
                                 Long msgSeq,
                                 String ackType,
                                 String body) {
        WsEnvelope ack = new WsEnvelope();
        ack.type = "ACK";
        ack.from = fromUserId;
        ack.clientMsgId = clientMsgId;
        ack.serverMsgId = serverMsgId;
        ack.msgSeq = msgSeq;
        ack.ackType = ackType;
        ack.body = body;
        ack.ts = Instant.now().toEpochMilli();
        return write(ctx, ack);
    }

    public ChannelFuture writeAck(Channel ch, long fromUserId, String clientMsgId, String serverMsgId, String ackType, String body) {
        WsEnvelope ack = new WsEnvelope();
        ack.type = "ACK";
        ack.from = fromUserId;
        ack.clientMsgId = clientMsgId;
        ack.serverMsgId = serverMsgId;
        ack.ackType = ackType;
        ack.body = body;
        ack.ts = Instant.now().toEpochMilli();
        return write(ch, ack);
    }

    public ChannelFuture writeAck(Channel ch,
                                 long fromUserId,
                                 String clientMsgId,
                                 String serverMsgId,
                                 Long msgSeq,
                                 String ackType,
                                 String body) {
        WsEnvelope ack = new WsEnvelope();
        ack.type = "ACK";
        ack.from = fromUserId;
        ack.clientMsgId = clientMsgId;
        ack.serverMsgId = serverMsgId;
        ack.msgSeq = msgSeq;
        ack.ackType = ackType;
        ack.body = body;
        ack.ts = Instant.now().toEpochMilli();
        return write(ch, ack);
    }

    public ChannelFuture writeAck(Channel ch,
                                 long fromUserId,
                                 String clientMsgId,
                                 String serverMsgId,
                                 String ackType,
                                 String body,
                                 LongConsumer eventLoopDelayNsConsumer) {
        WsEnvelope ack = new WsEnvelope();
        ack.type = "ACK";
        ack.from = fromUserId;
        ack.clientMsgId = clientMsgId;
        ack.serverMsgId = serverMsgId;
        ack.ackType = ackType;
        ack.body = body;
        ack.ts = Instant.now().toEpochMilli();
        return write(ch, ack, eventLoopDelayNsConsumer);
    }

    public ChannelFuture writeAck(Channel ch,
                                 long fromUserId,
                                 String clientMsgId,
                                 String serverMsgId,
                                 Long msgSeq,
                                 String ackType,
                                 String body,
                                 LongConsumer eventLoopDelayNsConsumer) {
        WsEnvelope ack = new WsEnvelope();
        ack.type = "ACK";
        ack.from = fromUserId;
        ack.clientMsgId = clientMsgId;
        ack.serverMsgId = serverMsgId;
        ack.msgSeq = msgSeq;
        ack.ackType = ackType;
        ack.body = body;
        ack.ts = Instant.now().toEpochMilli();
        return write(ch, ack, eventLoopDelayNsConsumer);
    }

    private ChannelFuture doWrite(ChannelHandlerContext ctx, WsEnvelope env) {
        try {
            Channel ch = ctx.channel();
            if (backpressureProps != null
                    && backpressureProps.enabledEffective()
                    && backpressureProps.dropWhenUnwritableEffective()
                    && ch != null
                    && !ch.isWritable()) {
                return ch.newFailedFuture(new IllegalStateException("ws backpressure: channel not writable"));
            }
            String json = objectMapper.writeValueAsString(env);
            return ctx.writeAndFlush(new TextWebSocketFrame(json));
        } catch (Exception e) {
            log.warn("ws serialize/write failed: {}", e.toString());
            return ctx.channel().newFailedFuture(e);
        }
    }

    private ChannelFuture doWrite(Channel ch, WsEnvelope env) {
        try {
            if (backpressureProps != null
                    && backpressureProps.enabledEffective()
                    && backpressureProps.dropWhenUnwritableEffective()
                    && !ch.isWritable()) {
                return ch.newFailedFuture(new IllegalStateException("ws backpressure: channel not writable"));
            }
            String json = objectMapper.writeValueAsString(env);
            return ch.writeAndFlush(new TextWebSocketFrame(json));
        } catch (Exception e) {
            log.warn("ws serialize/write failed: {}", e.toString());
            return ch.newFailedFuture(e);
        }
    }

    private ChannelFuture failFastIfUnwritable(Channel ch) {
        if (backpressureProps == null
                || !backpressureProps.enabledEffective()
                || !backpressureProps.dropWhenUnwritableEffective()
                || ch == null
                || ch.isWritable()) {
            return null;
        }
        // 提前失败：避免在慢端/高压场景下向 eventLoop 堆积大量 pending tasks。
        return ch.newFailedFuture(new IllegalStateException("ws backpressure: channel not writable"));
    }

    private static void safeAcceptDelayConsumer(LongConsumer consumer, long delayNs) {
        if (consumer == null) {
            return;
        }
        try {
            consumer.accept(delayNs);
        } catch (Exception ignored) {
        }
    }
}
