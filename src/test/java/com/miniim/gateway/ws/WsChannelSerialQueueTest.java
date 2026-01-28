package com.miniim.gateway.ws;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WsChannelSerialQueueTest {

    @Test
    void shouldRunTasksSequentially() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel();

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch allowFirstFinish = new CountDownLatch(1);
        AtomicBoolean secondStarted = new AtomicBoolean(false);

        CompletableFuture<Void> f1 = WsChannelSerialQueue.enqueue(ch, () -> CompletableFuture.runAsync(() -> {
            firstStarted.countDown();
            try {
                assertTrue(allowFirstFinish.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }));

        CompletableFuture<Void> f2 = WsChannelSerialQueue.enqueue(ch, () -> CompletableFuture.runAsync(() -> secondStarted.set(true)));

        pumpUntil(ch, firstStarted, 1000);
        TimeUnit.MILLISECONDS.sleep(80);
        assertFalse(secondStarted.get());

        allowFirstFinish.countDown();
        pumpUntilDone(ch, CompletableFuture.allOf(f1, f2), 2000);
        assertTrue(secondStarted.get());
    }

    @Test
    void shouldContinueAfterFailure() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel();

        AtomicBoolean secondRan = new AtomicBoolean(false);

        CompletableFuture<Void> f1 = WsChannelSerialQueue.enqueue(ch, () -> CompletableFuture.failedFuture(new RuntimeException("boom")));
        CompletableFuture<Void> f2 = WsChannelSerialQueue.enqueue(ch, () -> CompletableFuture.runAsync(() -> secondRan.set(true)));

        pumpUntilDoneIgnoreError(ch, f1, 1000);
        pumpUntilDone(ch, f2, 2000);
        assertTrue(secondRan.get());
    }

    private static void pumpUntil(EmbeddedChannel ch, CountDownLatch latch, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (latch.getCount() > 0 && System.currentTimeMillis() < deadline) {
            ch.runPendingTasks();
            TimeUnit.MILLISECONDS.sleep(5);
        }
        assertTrue(latch.getCount() == 0);
    }

    private static void pumpUntilDone(EmbeddedChannel ch, CompletableFuture<?> f, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!f.isDone() && System.currentTimeMillis() < deadline) {
            ch.runPendingTasks();
            TimeUnit.MILLISECONDS.sleep(5);
        }
        f.get(100, TimeUnit.MILLISECONDS);
    }

    private static void pumpUntilDoneIgnoreError(EmbeddedChannel ch, CompletableFuture<?> f, long timeoutMs) throws Exception {
        try {
            pumpUntilDone(ch, f, timeoutMs);
        } catch (Exception ignore) {
        }
    }
}
