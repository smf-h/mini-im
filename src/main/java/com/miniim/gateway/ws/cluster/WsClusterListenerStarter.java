package com.miniim.gateway.ws.cluster;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 让 Redis Pub/Sub 监听器在 Redis 不可用时也不阻断启动，并在后台重试启动。
 *
 * <p>这用于“Redis down 降级”场景：跨实例控制消息会暂时不可用，但网关核心功能仍可用。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsClusterListenerStarter implements SmartLifecycle {

    private static final ScheduledExecutorService RETRY_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-cluster-listener-retry");
        t.setDaemon(true);
        return t;
    });

    private final RedisMessageListenerContainer container;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicInteger attempt = new AtomicInteger(0);

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        tryStart(0);
    }

    private void tryStart(long delayMs) {
        RETRY_SCHEDULER.schedule(() -> {
            if (!started.get()) {
                return;
            }
            try {
                container.start();
                attempt.set(0);
                log.info("ws cluster listener started");
            } catch (Exception e) {
                int n = attempt.incrementAndGet();
                long nextDelayMs = backoffMs(n);
                log.warn("ws cluster listener start failed (attempt={}): {}", n, e.toString());
                tryStart(nextDelayMs);
            }
        }, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }

    private static long backoffMs(int attempt) {
        if (attempt <= 0) {
            return 200;
        }
        long v = 200L * (1L << Math.min(6, attempt - 1));
        return Math.min(5000L, Math.max(200L, v));
    }

    @Override
    public void stop() {
        started.set(false);
        try {
            container.stop();
        } catch (Exception e) {
            log.debug("stop ws cluster listener failed: {}", e.toString());
        }
    }

    @Override
    public boolean isRunning() {
        return started.get() && container.isRunning();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        // 尽量晚一点启动（避免阻断其他关键启动流程）。
        return Integer.MAX_VALUE;
    }
}

