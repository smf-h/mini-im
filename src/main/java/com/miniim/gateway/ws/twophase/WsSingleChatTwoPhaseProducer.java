package com.miniim.gateway.ws.twophase;

import com.miniim.gateway.config.WsSingleChatTwoPhaseProperties;
import com.miniim.gateway.session.WsRouteStore;
import com.miniim.gateway.ws.ClientMsgIdIdempotency;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class WsSingleChatTwoPhaseProducer {

    private static final String IDEM_REDIS_PREFIX = "im:idem:client_msg_id:";

    private final WsSingleChatTwoPhaseProperties props;
    private final StringRedisTemplate redis;
    private final WsRouteStore routeStore;
    private final ClientMsgIdIdempotency idempotency;
    private final WsSingleChatTwoPhaseLocalQueue localQueue;

    private final DefaultRedisScript<String> claimAndXaddScript = buildClaimAndXaddScript();

    @Value
    public static class EnqueueResult {
        boolean ok;
        boolean existed;
        String serverMsgId;
        String streamId;
        String error;
    }

    public EnqueueResult enqueueAccepted(long fromUserId,
                                         long toUserId,
                                         String clientMsgId,
                                         String serverMsgId,
                                         String msgType,
                                         String body,
                                         long sendTs) {
        if (fromUserId <= 0 || toUserId <= 0 || clientMsgId == null || clientMsgId.isBlank() || serverMsgId == null || serverMsgId.isBlank()) {
            return new EnqueueResult(false, false, serverMsgId, null, "bad_args");
        }

        String idemKey = idempotency.key(fromUserId, "SINGLE_CHAT", clientMsgId);
        if ("local".equals(props.modeEffective())) {
            return enqueueAcceptedLocal(fromUserId, toUserId, clientMsgId, serverMsgId, msgType, body, sendTs, idemKey);
        }

        String idemRedisKey = IDEM_REDIS_PREFIX + idemKey;
        long ttlSeconds = ttlSeconds();

        String acceptedStream = props.acceptedStreamKeyEffective();
        String producerServerId = routeStore.serverId();

        try {
            String out = redis.execute(
                    claimAndXaddScript,
                    List.of(idemRedisKey, acceptedStream),
                    serverMsgId,
                    String.valueOf(ttlSeconds),
                    String.valueOf(fromUserId),
                    String.valueOf(toUserId),
                    clientMsgId,
                    safeStr(msgType),
                    safeStr(body),
                    String.valueOf(sendTs),
                    producerServerId == null ? "" : producerServerId
            );
            if (out == null || out.isBlank()) {
                return new EnqueueResult(false, false, serverMsgId, null, "redis_nil");
            }

            int idx = out.indexOf('|');
            String tag = idx < 0 ? out : out.substring(0, idx);
            String val = idx < 0 ? "" : out.substring(idx + 1);
            if ("EXIST".equals(tag)) {
                String existedServerMsgId = val == null ? null : val.trim();
                if (existedServerMsgId != null && !existedServerMsgId.isBlank()) {
                    ClientMsgIdIdempotency.Claim existed = new ClientMsgIdIdempotency.Claim();
                    existed.setServerMsgId(existedServerMsgId);
                    idempotency.put(idemKey, existed);
                    return new EnqueueResult(true, true, existedServerMsgId, null, null);
                }
                return new EnqueueResult(false, true, serverMsgId, null, "exist_but_empty");
            }
            if ("NEW".equals(tag)) {
                ClientMsgIdIdempotency.Claim claim = new ClientMsgIdIdempotency.Claim();
                claim.setServerMsgId(serverMsgId);
                idempotency.put(idemKey, claim);
                return new EnqueueResult(true, false, serverMsgId, val, null);
            }
            return new EnqueueResult(false, false, serverMsgId, null, "redis_unknown:" + tag);
        } catch (Exception e) {
            log.debug("two-phase enqueue failed: from={}, to={}, clientMsgId={}, err={}", fromUserId, toUserId, clientMsgId, e.toString());
            return new EnqueueResult(false, false, serverMsgId, null, "redis_err");
        }
    }

    private EnqueueResult enqueueAcceptedLocal(long fromUserId,
                                              long toUserId,
                                              String clientMsgId,
                                              String serverMsgId,
                                              String msgType,
                                              String body,
                                              long sendTs,
                                              String idemKey) {
        ClientMsgIdIdempotency.Claim claim = new ClientMsgIdIdempotency.Claim();
        claim.setServerMsgId(serverMsgId);
        ClientMsgIdIdempotency.Claim existed = idempotency.putIfAbsent(idemKey, claim);
        if (existed != null) {
            return new EnqueueResult(true, true, existed.getServerMsgId(), null, null);
        }

        String producerServerId = routeStore.serverId();
        boolean ok = localQueue.acceptedQueue().offer(new WsSingleChatTwoPhaseLocalQueue.Item(
                fromUserId,
                toUserId,
                clientMsgId,
                serverMsgId,
                safeStr(msgType),
                safeStr(body),
                sendTs,
                producerServerId == null ? "" : producerServerId
        ));
        if (!ok) {
            idempotency.remove(idemKey);
            return new EnqueueResult(false, false, serverMsgId, null, "queue_full");
        }
        return new EnqueueResult(true, false, serverMsgId, "local", null);
    }

    private long ttlSeconds() {
        long sec = idempotency.getProps() == null ? 0 : idempotency.getProps().getExpireAfterAccessSeconds();
        if (sec <= 0) {
            sec = 1800;
        }
        return sec;
    }

    private static DefaultRedisScript<String> buildClaimAndXaddScript() {
        DefaultRedisScript<String> s = new DefaultRedisScript<>();
        s.setResultType(String.class);
        s.setScriptText("""
                local existed = redis.call('GET', KEYS[1])
                if existed then
                  return 'EXIST|' .. existed
                end

                redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
                local id = redis.call('XADD', KEYS[2], '*',
                  'serverMsgId', ARGV[1],
                  'fromUserId', ARGV[3],
                  'toUserId', ARGV[4],
                  'clientMsgId', ARGV[5],
                  'msgType', ARGV[6],
                  'body', ARGV[7],
                  'sendTs', ARGV[8],
                  'producerServerId', ARGV[9]
                )
                return 'NEW|' .. id
                """);
        return s;
    }

    private static String safeStr(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > 8192 ? t.substring(0, 8192) : t;
    }
}
