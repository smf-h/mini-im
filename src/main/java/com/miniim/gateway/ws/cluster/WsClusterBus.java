package com.miniim.gateway.ws.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniim.gateway.config.WsPerfTraceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class WsClusterBus {

    private static final String TOPIC_PREFIX = "im:gw:ctrl:";

    /**
     * Redis Pub/Sub 故障时做 fail-fast：避免每次 publish 都阻塞在 Redis 超时上。
     *
     * <p>跨实例控制消息在 Redis 宕机时本就无法保证，这里只保证“尽快失败/不拖垮业务线程”。</p>
     */
    private static final long REDIS_FAIL_FAST_MS = 10_000;
    private static final AtomicLong REDIS_UNAVAILABLE_UNTIL_MS = new AtomicLong(0);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final WsPerfTraceProperties perfTraceProps;

    public void publish(String serverId, WsClusterMessage msg) {
        if (serverId == null || serverId.isBlank() || msg == null) {
            return;
        }
        if (shouldFailFast()) {
            return;
        }
        long startNs = System.nanoTime();
        try {
            String json = objectMapper.writeValueAsString(msg);
            redis.convertAndSend(topic(serverId), json);
            maybeLogPerf(startNs, true, serverId, msg.type(), json == null ? 0 : json.length());
        } catch (Exception e) {
            log.debug("ws cluster publish failed: serverId={}, type={}, err={}", serverId, msg.type(), e.toString());
            markRedisDown();
            maybeLogPerf(startNs, false, serverId, msg.type(), 0);
        }
    }

    public void publishKick(String serverId, long userId, String connId, String reason) {
        publish(serverId, WsClusterMessage.kick(userId, connId, reason));
    }

    public void publishPush(String serverId, long userId, com.miniim.gateway.ws.WsEnvelope envelope) {
        publish(serverId, WsClusterMessage.push(userId, envelope));
    }

    public void publishPushBatch(String serverId, List<Long> userIds, com.miniim.gateway.ws.WsEnvelope envelope, int batchSize) {
        if (serverId == null || serverId.isBlank() || userIds == null || userIds.isEmpty() || envelope == null) {
            return;
        }
        int bs = Math.max(1, batchSize);
        List<Long> buf = new ArrayList<>(Math.min(bs, userIds.size()));
        for (Long uid : userIds) {
            if (uid == null || uid <= 0) {
                continue;
            }
            buf.add(uid);
            if (buf.size() >= bs) {
                publish(serverId, WsClusterMessage.pushBatch(List.copyOf(buf), envelope));
                buf.clear();
            }
        }
        if (!buf.isEmpty()) {
            publish(serverId, WsClusterMessage.pushBatch(List.copyOf(buf), envelope));
        }
    }

    public static String topic(String serverId) {
        return TOPIC_PREFIX + serverId;
    }

    private void maybeLogPerf(long startNs, boolean ok, String serverId, String type, int jsonLen) {
        if (perfTraceProps == null || !perfTraceProps.enabledEffective()) {
            return;
        }
        long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        long slowMs = perfTraceProps.slowMsEffective();
        double sampleRate = perfTraceProps.sampleRateEffective();
        boolean sampled = sampleRate > 0 && ThreadLocalRandom.current().nextDouble() < sampleRate;
        if (totalMs < slowMs && !sampled) {
            return;
        }
        log.info("ws_perf redis_pubsub ok={} serverId={} type={} totalMs={} jsonLen={}", ok, serverId, type, totalMs, jsonLen);
    }

    private static boolean shouldFailFast() {
        return System.currentTimeMillis() < REDIS_UNAVAILABLE_UNTIL_MS.get();
    }

    private static void markRedisDown() {
        long until = System.currentTimeMillis() + REDIS_FAIL_FAST_MS;
        while (true) {
            long prev = REDIS_UNAVAILABLE_UNTIL_MS.get();
            if (prev >= until) {
                return;
            }
            if (REDIS_UNAVAILABLE_UNTIL_MS.compareAndSet(prev, until)) {
                return;
            }
        }
    }
}
