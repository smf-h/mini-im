package com.miniim.gateway.ws;

import io.netty.channel.Channel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 为每个 Netty Channel 维护一个串行队列（Future 链），用于保证同一连接入站消息的处理顺序。
 *
 * <p>注意：队列会“吞掉”上一任务的异常以保证后续任务继续执行；但返回给调用方的 future 会保留异常。</p>
 */
public final class WsChannelSerialQueue {

    private static final AttributeKey<AtomicReference<CompletableFuture<Void>>> ATTR_TAIL_REF =
            AttributeKey.valueOf("im:ws:serial_queue:tail");

    private WsChannelSerialQueue() {
    }

    public static CompletableFuture<Void> enqueue(Channel channel, Supplier<? extends CompletionStage<?>> taskSupplier) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(taskSupplier, "taskSupplier");

        AtomicReference<CompletableFuture<Void>> tailRef = tailRef(channel);

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

    private static AtomicReference<CompletableFuture<Void>> tailRef(Channel channel) {
        Attribute<AtomicReference<CompletableFuture<Void>>> attr = channel.attr(ATTR_TAIL_REF);
        AtomicReference<CompletableFuture<Void>> existing = attr.get();
        if (existing != null) {
            return existing;
        }

        AtomicReference<CompletableFuture<Void>> created = new AtomicReference<>(CompletableFuture.completedFuture(null));
        AtomicReference<CompletableFuture<Void>> raced = attr.setIfAbsent(created);
        return raced == null ? created : raced;
    }
}
