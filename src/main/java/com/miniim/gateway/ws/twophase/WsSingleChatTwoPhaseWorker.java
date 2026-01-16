package com.miniim.gateway.ws.twophase;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.miniim.domain.entity.MessageEntity;
import com.miniim.domain.enums.ChatType;
import com.miniim.domain.enums.MessageStatus;
import com.miniim.domain.enums.MessageType;
import com.miniim.domain.service.MessageService;
import com.miniim.domain.service.SingleChatService;
import com.miniim.gateway.config.WsSingleChatTwoPhaseProperties;
import com.miniim.gateway.session.WsRouteStore;
import com.miniim.gateway.ws.WsEnvelope;
import com.miniim.gateway.ws.WsPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
@Slf4j
public class WsSingleChatTwoPhaseWorker implements SmartLifecycle {

    private static final String DELIVER_LOCK_KEY = "im:lock:single_chat:deliver_leader";
    private static final String SAVE_LOCK_KEY = "im:lock:single_chat:save_leader";

    private final WsSingleChatTwoPhaseProperties props;
    private final StringRedisTemplate redis;
    private final WsRouteStore routeStore;
    private final WsPushService wsPushService;
    private final SingleChatService singleChatService;
    private final MessageService messageService;
    private final WsSingleChatTwoPhaseLocalQueue localQueue;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;

    @Override
    public void start() {
        if (running.getAndSet(true)) {
            return;
        }
        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "ws-two-phase-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });

        ensureGroups();

        executor.submit(this::deliverLoop);
        executor.submit(this::saveLoop);
    }

    @Override
    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }
        ExecutorService es = executor;
        if (es != null) {
            es.shutdownNow();
            try {
                es.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private void ensureGroups() {
        if (props == null || !props.enabledEffective()) {
            return;
        }
        if ("local".equals(props.modeEffective())) {
            return;
        }
        String accepted = props.acceptedStreamKeyEffective();
        String toSave = props.toSaveStreamKeyEffective();
        String deliverGroup = props.deliverGroupEffective();
        String saveGroup = props.saveGroupEffective();

        tryCreateGroup(accepted, deliverGroup);
        tryCreateGroup(accepted, saveGroup);
        tryCreateGroup(toSave, saveGroup);
    }

    private void tryCreateGroup(String streamKey, String group) {
        if (streamKey == null || streamKey.isBlank() || group == null || group.isBlank()) {
            return;
        }
        try (RedisConnection conn = redis.getConnectionFactory().getConnection()) {
            // XGROUP CREATE <stream> <group> $ MKSTREAM
            conn.execute("XGROUP",
                    "CREATE".getBytes(StandardCharsets.UTF_8),
                    streamKey.getBytes(StandardCharsets.UTF_8),
                    group.getBytes(StandardCharsets.UTF_8),
                    "$".getBytes(StandardCharsets.UTF_8),
                    "MKSTREAM".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            String msg = e.toString();
            if (msg.contains("BUSYGROUP") || msg.contains("Consumer Group name already exists")) {
                return;
            }
            log.debug("two-phase create group failed: stream={}, group={}, err={}", streamKey, group, msg);
        }
    }

    private void deliverLoop() {
        String serverId = routeStore.serverId();
        String consumer = "deliver@" + (serverId == null ? "unknown" : serverId);
        RedisLeaderLock leader = new RedisLeaderLock(redis, DELIVER_LOCK_KEY, consumer);

        while (running.get()) {
            try {
                if (props == null || !props.enabledEffective() || !props.deliverBeforeSavedEffective()) {
                    Thread.sleep(200);
                    continue;
                }

                if ("local".equals(props.modeEffective())) {
                    WsSingleChatTwoPhaseLocalQueue.Item item = localQueue.acceptedQueue().poll(200, TimeUnit.MILLISECONDS);
                    if (item == null) {
                        continue;
                    }
                    deliverOneLocal(item);
                    continue;
                }

                Duration ttl = Duration.ofMillis(props.leaderLockTtlMsEffective());
                if (!leader.tryAcquire(ttl)) {
                    if (!leader.renew(ttl)) {
                        Thread.sleep(200);
                        continue;
                    }
                }

                String accepted = props.acceptedStreamKeyEffective();
                String group = props.deliverGroupEffective();
                int batch = props.batchSizeEffective();
                long blockMs = props.blockMsEffective();

                List<MapRecord<String, Object, Object>> records = readBatch(accepted, group, consumer, batch, blockMs);
                if (records == null || records.isEmpty()) {
                    continue;
                }

                for (MapRecord<String, Object, Object> r : records) {
                    if (!running.get()) {
                        break;
                    }
                    deliverOne(r);
                    ack(accepted, group, r.getId());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.debug("two-phase deliver loop error: {}", e.toString());
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void saveLoop() {
        String serverId = routeStore.serverId();
        String consumer = "save@" + (serverId == null ? "unknown" : serverId);
        RedisLeaderLock leader = new RedisLeaderLock(redis, SAVE_LOCK_KEY, consumer);

        while (running.get()) {
            try {
                if (props == null || !props.enabledEffective()) {
                    Thread.sleep(200);
                    continue;
                }

                if ("local".equals(props.modeEffective())) {
                    boolean deliverFirst = props.deliverBeforeSavedEffective();
                    WsSingleChatTwoPhaseLocalQueue.Item item = (deliverFirst ? localQueue.toSaveQueue() : localQueue.acceptedQueue())
                            .poll(200, TimeUnit.MILLISECONDS);
                    if (item == null) {
                        continue;
                    }
                    saveOneLocal(item, !deliverFirst);
                    continue;
                }

                Duration ttl = Duration.ofMillis(props.leaderLockTtlMsEffective());
                if (!leader.tryAcquire(ttl)) {
                    if (!leader.renew(ttl)) {
                        Thread.sleep(200);
                        continue;
                    }
                }

                boolean deliverFirst = props.deliverBeforeSavedEffective();
                String streamKey = deliverFirst ? props.toSaveStreamKeyEffective() : props.acceptedStreamKeyEffective();
                String group = props.saveGroupEffective();
                int batch = props.batchSizeEffective();
                long blockMs = props.blockMsEffective();

                List<MapRecord<String, Object, Object>> records = readBatch(streamKey, group, consumer, batch, blockMs);
                if (records == null || records.isEmpty()) {
                    continue;
                }

                for (MapRecord<String, Object, Object> r : records) {
                    if (!running.get()) {
                        break;
                    }
                    saveOne(r, !deliverFirst);
                    ack(streamKey, group, r.getId());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.debug("two-phase save loop error: {}", e.toString());
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private List<MapRecord<String, Object, Object>> readBatch(String streamKey,
                                                              String group,
                                                              String consumer,
                                                              int batch,
                                                              long blockMs) {
        try {
            return redis.opsForStream().read(
                    Consumer.from(group, consumer),
                    StreamReadOptions.empty().count(batch).block(Duration.ofMillis(blockMs)),
                    StreamOffset.create(streamKey, ReadOffset.lastConsumed())
            );
        } catch (Exception e) {
            return List.of();
        }
    }

    private void ack(String streamKey, String group, RecordId id) {
        try {
            redis.opsForStream().acknowledge(streamKey, group, id);
        } catch (Exception ignored) {
        }
    }

    private void deliverOne(MapRecord<String, Object, Object> r) {
        Map<Object, Object> v = r.getValue();
        long from = asLong(v.get("fromUserId"));
        long to = asLong(v.get("toUserId"));
        String clientMsgId = asString(v.get("clientMsgId"));
        String serverMsgId = asString(v.get("serverMsgId"));
        String msgType = asString(v.get("msgType"));
        String body = asString(v.get("body"));
        long sendTs = asLong(v.get("sendTs"));

        WsEnvelope out = new WsEnvelope();
        out.setType("SINGLE_CHAT");
        out.setFrom(from > 0 ? from : null);
        out.setTo(to > 0 ? to : null);
        out.setClientMsgId(clientMsgId);
        out.setServerMsgId(serverMsgId);
        out.setMsgType(msgType);
        out.setBody(body);
        out.setTs(sendTs > 0 ? sendTs : Instant.now().toEpochMilli());

        try {
            wsPushService.pushToUser(to, out);
        } catch (Exception e) {
            log.debug("two-phase deliver failed: to={}, serverMsgId={}, err={}", to, serverMsgId, e.toString());
        }

        // 转交给落库队列
        Map<String, String> payload = new HashMap<>();
        payload.put("serverMsgId", serverMsgId);
        payload.put("fromUserId", String.valueOf(from));
        payload.put("toUserId", String.valueOf(to));
        payload.put("clientMsgId", clientMsgId);
        payload.put("msgType", msgType);
        payload.put("body", body);
        payload.put("sendTs", String.valueOf(sendTs));
        payload.put("acceptedStreamId", r.getId().getValue());
        payload.put("deliveredAtMs", String.valueOf(System.currentTimeMillis()));
        payload.put("producerServerId", asString(v.get("producerServerId")));
        try {
            redis.opsForStream().add(StreamRecords.mapBacked(payload).withStreamKey(props.toSaveStreamKeyEffective()));
        } catch (Exception e) {
            log.debug("two-phase enqueue to_save failed: serverMsgId={}, err={}", serverMsgId, e.toString());
        }
    }

    private void deliverOneLocal(WsSingleChatTwoPhaseLocalQueue.Item item) {
        long from = item.fromUserId();
        long to = item.toUserId();

        WsEnvelope out = new WsEnvelope();
        out.setType("SINGLE_CHAT");
        out.setFrom(from);
        out.setTo(to);
        out.setClientMsgId(item.clientMsgId());
        out.setServerMsgId(item.serverMsgId());
        out.setMsgType(item.msgType());
        out.setBody(item.body());
        out.setTs(item.sendTs() > 0 ? item.sendTs() : Instant.now().toEpochMilli());

        try {
            wsPushService.pushToUser(to, out);
        } catch (Exception e) {
            log.debug("two-phase deliver(local) failed: to={}, serverMsgId={}, err={}", to, item.serverMsgId(), e.toString());
        }

        localQueue.toSaveQueue().offer(item);
    }

    private void saveOne(MapRecord<String, Object, Object> r, boolean deliverAfterSaved) {
        Map<Object, Object> v = r.getValue();
        long from = asLong(v.get("fromUserId"));
        long to = asLong(v.get("toUserId"));
        String clientMsgId = asString(v.get("clientMsgId"));
        String serverMsgId = asString(v.get("serverMsgId"));
        String msgType = asString(v.get("msgType"));
        String body = asString(v.get("body"));
        long sendTs = asLong(v.get("sendTs"));

        long msgId = parseMsgId(serverMsgId);

        try {
            Long user1Id = Math.min(from, to);
            Long user2Id = Math.max(from, to);
            Long singleChatId = singleChatService.getOrCreateSingleChatId(user1Id, user2Id);

            MessageEntity entity = new MessageEntity();
            entity.setId(msgId);
            entity.setChatType(ChatType.SINGLE);
            entity.setSingleChatId(singleChatId);
            entity.setFromUserId(from);
            entity.setToUserId(to);
            MessageType mt = MessageType.fromString(msgType);
            entity.setMsgType(mt == null ? MessageType.TEXT : mt);
            entity.setStatus(MessageStatus.SAVED);
            entity.setContent(body);
            entity.setClientMsgId(clientMsgId);
            entity.setServerMsgId(serverMsgId);

            messageService.save(entity);
        } catch (DuplicateKeyException e) {
            // 幂等：重复落库视为成功
        } catch (Exception e) {
            log.warn("two-phase save failed: serverMsgId={}, err={}", serverMsgId, e.toString());
            return;
        }

        // saved 回执给发送者（通过路由跨实例投递）
        WsEnvelope ack = new WsEnvelope();
        ack.setType("ACK");
        ack.setFrom(from);
        ack.setClientMsgId(clientMsgId);
        ack.setServerMsgId(serverMsgId);
        ack.setAckType("saved");
        ack.setBody(null);
        ack.setTs(Instant.now().toEpochMilli());
        try {
            wsPushService.pushToUser(from, ack);
        } catch (Exception e) {
            log.debug("two-phase saved ack push failed: from={}, serverMsgId={}, err={}", from, serverMsgId, e.toString());
        }

        if (deliverAfterSaved) {
            WsEnvelope out = new WsEnvelope();
            out.setType("SINGLE_CHAT");
            out.setFrom(from);
            out.setTo(to);
            out.setClientMsgId(clientMsgId);
            out.setServerMsgId(serverMsgId);
            out.setMsgType(msgType);
            out.setBody(body);
            out.setTs(sendTs > 0 ? sendTs : Instant.now().toEpochMilli());
            try {
                wsPushService.pushToUser(to, out);
            } catch (Exception e) {
                log.debug("two-phase deliver-after-save failed: to={}, serverMsgId={}, err={}", to, serverMsgId, e.toString());
            }
        }
    }

    private void saveOneLocal(WsSingleChatTwoPhaseLocalQueue.Item item, boolean deliverAfterSaved) {
        long from = item.fromUserId();
        long to = item.toUserId();
        String clientMsgId = item.clientMsgId();
        String serverMsgId = item.serverMsgId();
        String msgType = item.msgType();
        String body = item.body();
        long sendTs = item.sendTs();

        long msgId = parseMsgId(serverMsgId);

        try {
            Long user1Id = Math.min(from, to);
            Long user2Id = Math.max(from, to);
            Long singleChatId = singleChatService.getOrCreateSingleChatId(user1Id, user2Id);

            MessageEntity entity = new MessageEntity();
            entity.setId(msgId);
            entity.setChatType(ChatType.SINGLE);
            entity.setSingleChatId(singleChatId);
            entity.setFromUserId(from);
            entity.setToUserId(to);
            MessageType mt = MessageType.fromString(msgType);
            entity.setMsgType(mt == null ? MessageType.TEXT : mt);
            entity.setStatus(MessageStatus.SAVED);
            entity.setContent(body);
            entity.setClientMsgId(clientMsgId);
            entity.setServerMsgId(serverMsgId);

            messageService.save(entity);
        } catch (DuplicateKeyException e) {
            // ok
        } catch (Exception e) {
            log.warn("two-phase save(local) failed: serverMsgId={}, err={}", serverMsgId, e.toString());
            return;
        }

        WsEnvelope ack = new WsEnvelope();
        ack.setType("ACK");
        ack.setFrom(from);
        ack.setClientMsgId(clientMsgId);
        ack.setServerMsgId(serverMsgId);
        ack.setAckType("saved");
        ack.setBody(null);
        ack.setTs(Instant.now().toEpochMilli());
        try {
            wsPushService.pushToUser(from, ack);
        } catch (Exception e) {
            log.debug("two-phase saved ack push(local) failed: from={}, serverMsgId={}, err={}", from, serverMsgId, e.toString());
        }

        if (deliverAfterSaved) {
            WsEnvelope out = new WsEnvelope();
            out.setType("SINGLE_CHAT");
            out.setFrom(from);
            out.setTo(to);
            out.setClientMsgId(clientMsgId);
            out.setServerMsgId(serverMsgId);
            out.setMsgType(msgType);
            out.setBody(body);
            out.setTs(sendTs > 0 ? sendTs : Instant.now().toEpochMilli());
            try {
                wsPushService.pushToUser(to, out);
            } catch (Exception e) {
                log.debug("two-phase deliver-after-save(local) failed: to={}, serverMsgId={}, err={}", to, serverMsgId, e.toString());
            }
        }
    }

    private static long parseMsgId(String serverMsgId) {
        if (serverMsgId != null) {
            try {
                return Long.parseLong(serverMsgId);
            } catch (NumberFormatException ignore) {
            }
        }
        return IdWorker.getId();
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignore) {
            }
        }
        return 0;
    }
}
