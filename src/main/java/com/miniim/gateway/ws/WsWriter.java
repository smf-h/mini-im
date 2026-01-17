package com.miniim.gateway.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniim.gateway.config.WsBackpressureProperties;
import com.miniim.gateway.config.WsEncodeProperties;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
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

    private static final AttributeKey<AtomicReference<CompletableFuture<Void>>> ATTR_OUT_TAIL_REF =
            AttributeKey.valueOf("im:ws:out_queue:tail");

    private final ObjectMapper objectMapper;
    private final WsBackpressureProperties backpressureProps;
    private final WsEncodeProperties encodeProps;
    @Qualifier("imWsEncodeExecutor")
    private final Executor encodeExecutor;

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
        if (encodeProps == null || !encodeProps.enabledEffective() || !encodeProps.encodeOnExecutorEffective()) {
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

        ChannelPromise promise = ch.newPromise();
        long enqueuedAtNs = System.nanoTime();
        enqueueOutboundEncodeThenWrite(ch, env, promise, enqueuedAtNs, eventLoopDelayNsConsumer).whenComplete((v, e) -> {
            if (e != null && !promise.isDone()) {
                promise.setFailure(e);
            }
        });
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

    private CompletionStage<Void> encodeThenWrite(Channel ch, WsEnvelope env, ChannelPromise outPromise) {
        CompletableFuture<Void> stage = new CompletableFuture<>();
        if (outPromise == null) {
            stage.completeExceptionally(new IllegalArgumentException("outPromise is null"));
            return stage;
        }

        ChannelFuture fastFail = failFastIfUnwritable(ch);
        if (fastFail != null) {
            outPromise.setFailure(fastFail.cause());
            stage.completeExceptionally(fastFail.cause());
            return stage;
        }

        CompletableFuture<TextWebSocketFrame> encoded;
        try {
            encoded = CompletableFuture.supplyAsync(() -> encode(env), encodeExecutor);
        } catch (RejectedExecutionException e) {
            outPromise.setFailure(e);
            stage.completeExceptionally(e);
            return stage;
        } catch (Exception e) {
            outPromise.setFailure(e);
            stage.completeExceptionally(e);
            return stage;
        }

        encoded.whenComplete((frame, encErr) -> {
            if (encErr != null) {
                outPromise.setFailure(encErr);
                stage.completeExceptionally(encErr);
                return;
            }
            ch.eventLoop().execute(() -> writePrepared(ch, frame, outPromise).whenComplete((v, e) -> {
                if (e != null) {
                    stage.completeExceptionally(e);
                } else {
                    stage.complete(null);
                }
            }));
        });

        return stage;
    }

    private CompletionStage<Void> enqueueOutboundEncodeThenWrite(Channel ch,
                                                                WsEnvelope env,
                                                                ChannelPromise outPromise,
                                                                long enqueuedAtNs,
                                                                LongConsumer eventLoopDelayNsConsumer) {
        Objects.requireNonNull(ch, "channel");

        AtomicReference<CompletableFuture<Void>> tailRef = outTailRef(ch);

        while (true) {
            CompletableFuture<Void> prevTail = tailRef.get();

            CompletableFuture<Void> taskFuture = prevTail
                    .handle((v, e) -> null)
                    .thenCompose(ignored -> encodeAsync(env))
                    .thenCompose(frame -> invokeOnEventLoopWritePrepared(ch, frame, outPromise, enqueuedAtNs, eventLoopDelayNsConsumer));

            CompletableFuture<Void> newTail = taskFuture.handle((v, e) -> null);

            if (tailRef.compareAndSet(prevTail, newTail)) {
                return taskFuture;
            }
        }
    }

    private CompletableFuture<TextWebSocketFrame> encodeAsync(WsEnvelope env) {
        try {
            return CompletableFuture.supplyAsync(() -> encode(env), encodeExecutor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<Void> invokeOnEventLoopWritePrepared(Channel ch,
                                                                  TextWebSocketFrame frame,
                                                                  ChannelPromise outPromise,
                                                                  long enqueuedAtNs,
                                                                  LongConsumer eventLoopDelayNsConsumer) {
        CompletableFuture<Void> out = new CompletableFuture<>();
        try {
            ch.eventLoop().execute(() -> {
                safeAcceptDelayConsumer(eventLoopDelayNsConsumer, System.nanoTime() - enqueuedAtNs);
                writePrepared(ch, frame, outPromise).whenComplete((v, e) -> {
                    if (e != null) {
                        out.completeExceptionally(e);
                    } else {
                        out.complete(null);
                    }
                });
            });
        } catch (Exception e) {
            try {
                frame.release();
            } catch (Exception ignored) {
            }
            out.completeExceptionally(e);
        }
        return out;
    }

    private CompletionStage<Void> writePrepared(Channel ch, TextWebSocketFrame frame, ChannelPromise outPromise) {
        CompletableFuture<Void> stage = new CompletableFuture<>();
        ChannelFuture fastFail2 = failFastIfUnwritable(ch);
        if (fastFail2 != null) {
            frame.release();
            Throwable cause = fastFail2.cause();
            if (cause == null) {
                cause = new IllegalStateException("ws backpressure: channel not writable");
            }
            outPromise.setFailure(cause);
            stage.completeExceptionally(cause);
            return stage;
        }
        ch.writeAndFlush(frame).addListener(f -> {
            if (f.isSuccess()) {
                outPromise.setSuccess();
                stage.complete(null);
            } else {
                Throwable cause = f.cause();
                outPromise.setFailure(cause);
                stage.completeExceptionally(cause);
            }
        });
        return stage;
    }

    private TextWebSocketFrame encode(WsEnvelope env) {
        try {
            if (encodeProps != null && encodeProps.useBytesEffective()) {
                byte[] bytes = objectMapper.writeValueAsBytes(env);
                ByteBuf buf = Unpooled.wrappedBuffer(bytes);
                return new TextWebSocketFrame(buf);
            }
            String json = objectMapper.writeValueAsString(env);
            return new TextWebSocketFrame(json);
        } catch (Exception e) {
            throw new IllegalStateException("ws encode failed", e);
        }
    }

    private static CompletableFuture<Void> enqueueOutbound(Channel channel, Supplier<? extends CompletionStage<?>> taskSupplier) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(taskSupplier, "taskSupplier");

        AtomicReference<CompletableFuture<Void>> tailRef = outTailRef(channel);

        while (true) {
            CompletableFuture<Void> prevTail = tailRef.get();

            CompletableFuture<Void> taskFuture = prevTail
                    .handle((v, e) -> null)
                    .thenCompose(ignored -> invokeOnEventLoop(channel, taskSupplier));

            CompletableFuture<Void> newTail = taskFuture.handle((v, e) -> null);

            if (tailRef.compareAndSet(prevTail, newTail)) {
                return taskFuture;
            }
        }
    }

    private static CompletableFuture<Void> invokeOnEventLoop(Channel channel, Supplier<? extends CompletionStage<?>> taskSupplier) {
        CompletableFuture<Void> out = new CompletableFuture<>();
        channel.eventLoop().execute(() -> safeGet(taskSupplier).whenComplete((v, e) -> {
            if (e != null) {
                out.completeExceptionally(e);
            } else {
                out.complete(null);
            }
        }));
        return out;
    }

    private static CompletableFuture<Void> safeGet(Supplier<? extends CompletionStage<?>> taskSupplier) {
        try {
            CompletionStage<?> stage = taskSupplier.get();
            if (stage == null) {
                return CompletableFuture.completedFuture(null);
            }
            return stage.thenRun(() -> {
            }).toCompletableFuture();
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    private static AtomicReference<CompletableFuture<Void>> outTailRef(Channel channel) {
        Attribute<AtomicReference<CompletableFuture<Void>>> attr = channel.attr(ATTR_OUT_TAIL_REF);
        AtomicReference<CompletableFuture<Void>> existing = attr.get();
        if (existing != null) {
            return existing;
        }

        AtomicReference<CompletableFuture<Void>> created = new AtomicReference<>(CompletableFuture.completedFuture(null));
        AtomicReference<CompletableFuture<Void>> raced = attr.setIfAbsent(created);
        return raced == null ? created : raced;
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
