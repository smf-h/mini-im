import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * WebSocket 单聊端到端冒烟测试（脚本级验证，不依赖 Redis，不依赖 HTTP 登录接口）。
 *
 * <h2>为什么需要这个脚本</h2>
 * 你的单聊可靠性链路依赖多个环节协同：
 * <ul>
 *   <li>发件人 -> 服务端：服务端落库成功后回 ACK(saved)</li>
 *   <li>服务端 -> 收件人：收件人收到消息后回 ACK(ack_receive/received)</li>
 *   <li>服务端：收到 ACK_RECEIVED 后推进 DB 状态到 RECEIVED</li>
 *   <li>离线：收件人不在线时落库为 DROPPED；收件人上线后拉取/补发并再次走 ACK_RECEIVED</li>
 * </ul>
 * 这个脚本把这些关键路径跑一遍，并将每一步的“预期与失败原因”都体现在输出 JSON 里。
 *
 * <h2>鉴权方式</h2>
 * - 通过 HTTP Upgrade 阶段：Authorization: Bearer &lt;accessToken&gt; 进行握手鉴权（WsHandshakeAuthHandler）
 * - 握手后补发一帧 AUTH（兼容旧客户端/方便调试）：{"type":"AUTH","token":"..."}（WsFrameHandler）
 *
 * <h2>输出</h2>
 * 输出一行 JSON，包含：
 * - ok：整体是否通过
 * - scenarios：每个子场景的执行结果（包含 serverMsgId/clientMsgId，方便你去 DB 查）
 *
 * <h2>运行参数（通过 -Dxxx 传入）</h2>
 * -Dws=ws://127.0.0.1:9001/ws
 * -DjwtSecret=change-me-please-change-me-please-change-me
 * -DuserA=10001 -DuserB=10002
 * -DtimeoutMs=8000
 * -Dscenario=basic|idempotency|offline|all
 */
public class WsSmokeTest {

    private static final String WS_URL = System.getProperty("ws", "ws://127.0.0.1:9001/ws");
    private static final String JWT_SECRET = System.getProperty("jwtSecret", "change-me-please-change-me-please-change-me");

    private static final long USER_A = Long.parseLong(System.getProperty("userA", "10001"));
    private static final long USER_B = Long.parseLong(System.getProperty("userB", "10002"));

    private static final long TIMEOUT_MS = Long.parseLong(System.getProperty("timeoutMs", "8000"));
    private static final String SCENARIO = System.getProperty("scenario", "all").trim().toLowerCase();

    public static void main(String[] args) throws Exception {
        String tokenA = issueAccessToken(USER_A);
        String tokenB = issueAccessToken(USER_B);

        // 为了输出更可读且稳定，使用 LinkedHashMap 固定字段顺序
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> explain = new LinkedHashMap<>();
        explain.put("ackType.saved", "服务端确认消息已落库（写库成功），发件人可认为“已保存”，可用于重发幂等");
        explain.put("ackType.ack_receive/received", "收件人确认“已收到消息帧”，服务端可推进消息状态到 RECEIVED");
        explain.put("wsFrame.ERROR", "协议/鉴权/参数错误等，reason 字段给出原因（unauthorized/token_expired/...)");
        explain.put("dbStatus.1", "SAVED：已落库（等待投递/等待收件人 ACK_RECEIVED）");
        explain.put("dbStatus.5", "RECEIVED：已收到收件人 ACK_RECEIVED");
        explain.put("dbStatus.6", "DROPPED：收件人离线（或投递兜底失败），等待上线补发/再投递");
        out.put("explain", explain);

        Map<String, Object> scenarios = new LinkedHashMap<>();
        out.put("scenarios", scenarios);

        boolean ok = true;
        try {
            if ("basic".equals(SCENARIO) || "all".equals(SCENARIO)) {
                scenarios.put("basic", scenarioBasic(tokenA, tokenB));
            }
            if ("idempotency".equals(SCENARIO) || "all".equals(SCENARIO)) {
                scenarios.put("idempotency", scenarioIdempotency(tokenA, tokenB));
            }
            if ("offline".equals(SCENARIO) || "all".equals(SCENARIO)) {
                scenarios.put("offline_drop_and_recover", scenarioOfflineDropRecover(tokenA, tokenB));
            }
        } catch (Exception e) {
            ok = false;
            out.put("error", String.valueOf(e));
        }

        // 如果某个子场景失败，将整体 ok 标记为 false
        for (Object v : scenarios.values()) {
            if (v instanceof Map<?, ?> m) {
                Object sOk = m.get("ok");
                if (sOk instanceof Boolean b && !b) {
                    ok = false;
                }
            }
        }
        out.put("ok", ok);
        System.out.println(toJson(out));
    }

    /**
     * 场景 1：基础链路
     * 目标：
     * - A -> 服务端：拿到 ACK(saved)（证明落库成功）
     * - B 收到消息后回 ACK(ack_receive)
     * - 服务端应推进消息状态为 RECEIVED（DB 校验在 PowerShell 脚本里做）
     */
    private static Map<String, Object> scenarioBasic(String tokenA, String tokenB) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", false);
        r.put("why", "验证单聊基础 ACK 链路：ACK(saved) + ACK_RECEIVED");

        HttpClient httpClient = HttpClient.newHttpClient();
        QueueListener listenerA = new QueueListener();
        QueueListener listenerB = new QueueListener();

        WebSocket wsA = null;
        WebSocket wsB = null;
        try {
            wsA = connect(httpClient, tokenA, listenerA);
            wsB = connect(httpClient, tokenB, listenerB);
            auth(wsA, tokenA, listenerA.queue);
            auth(wsB, tokenB, listenerB.queue);

            String clientMsgId = "basic-" + UUID.randomUUID();
            sendSingleChat(wsA, clientMsgId, USER_B, "hello-basic");

            String ackSaved = await(listenerA.queue, TIMEOUT_MS, isAckSaved(clientMsgId));
            String serverMsgId = requireField(ackSaved, "serverMsgId");

            await(listenerB.queue, TIMEOUT_MS, isSingleChat(clientMsgId, serverMsgId));
            sendAckReceive(wsB, clientMsgId, serverMsgId, USER_A);

            // 给服务端异步 DB 更新一点时间（服务端用 dbExecutor）
            Thread.sleep(800);

            r.put("ok", true);
            r.put("clientMsgId", clientMsgId);
            r.put("serverMsgId", serverMsgId);
            r.put("expected", "A收到ACK(saved); B收到消息并回ACK(ack_receive); DB状态应变为RECEIVED(5)");
            return r;
        } catch (Exception e) {
            r.put("reason", "常见原因：WS鉴权失败(jwtSecret/iat/exp不一致)、服务端未启动、协议字段缺失、超时未收到ACK等");
            r.put("error", String.valueOf(e));
            return r;
        } finally {
            close(wsA);
            close(wsB);
        }
    }

    /**
     * 场景 2：幂等重发（clientMsgId 幂等）
     * 目标：
     * - A 连续发送两次相同 clientMsgId 的 SINGLE_CHAT
     * - 服务端应返回相同 serverMsgId（表示服务端去重成功）
     *
     * 备注：
     * - 这是“发件人未收到 ACK(saved) 时重发”的底层保障：即便发件人重发，服务端也不会重复落库。
     */
    private static Map<String, Object> scenarioIdempotency(String tokenA, String tokenB) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", false);
        r.put("why", "验证 clientMsgId 幂等：同一 clientMsgId 重发应返回同一 serverMsgId");

        HttpClient httpClient = HttpClient.newHttpClient();
        QueueListener listenerA = new QueueListener();
        QueueListener listenerB = new QueueListener();

        WebSocket wsA = null;
        WebSocket wsB = null;
        try {
            wsA = connect(httpClient, tokenA, listenerA);
            wsB = connect(httpClient, tokenB, listenerB);
            auth(wsA, tokenA, listenerA.queue);
            auth(wsB, tokenB, listenerB.queue);

            String clientMsgId = "idem-" + UUID.randomUUID();

            sendSingleChat(wsA, clientMsgId, USER_B, "hello-idem-1");
            String ack1 = await(listenerA.queue, TIMEOUT_MS, isAckSaved(clientMsgId));
            String serverMsgId1 = requireField(ack1, "serverMsgId");

            // 立即“重发同一个 clientMsgId”
            sendSingleChat(wsA, clientMsgId, USER_B, "hello-idem-2");
            String ack2 = await(listenerA.queue, TIMEOUT_MS, isAckSaved(clientMsgId));
            String serverMsgId2 = requireField(ack2, "serverMsgId");

            boolean same = serverMsgId1.equals(serverMsgId2);
            if (!same) {
                throw new RuntimeException("idempotency violated: serverMsgId changed");
            }

            // 清理：让 B 把其中一条消息 ACK_RECEIVED 掉，避免 DB 一直积压（不严格要求）
            await(listenerB.queue, TIMEOUT_MS, isSingleChat(clientMsgId, serverMsgId1));
            sendAckReceive(wsB, clientMsgId, serverMsgId1, USER_A);
            Thread.sleep(300);

            r.put("ok", true);
            r.put("clientMsgId", clientMsgId);
            r.put("serverMsgId", serverMsgId1);
            r.put("expected", "两次 ACK(saved) 的 serverMsgId 相同");
            return r;
        } catch (Exception e) {
            r.put("reason", "常见原因：服务端未启用幂等缓存、clientMsgId 未正确传递、超时未收到 ACK(saved)");
            r.put("error", String.valueOf(e));
            return r;
        } finally {
            close(wsA);
            close(wsB);
        }
    }

    /**
     * 场景 3：收件人离线（DROPPED）→ 上线补发 → ACK_RECEIVED
     * 目标：
     * - 先只连接 A，不连接 B（模拟 B 离线）
     * - A 发送 SINGLE_CHAT：服务端仍应回 ACK(saved)，并在 DB 中落库为 DROPPED（DB 校验在 PowerShell 脚本里做）
     * - B 再上线并 AUTH：服务端应把 DROPPED 消息补发给 B（你当前实现是在 AUTH 后主动拉取/推送）
     * - B 回 ACK_RECEIVED：服务端应推进状态为 RECEIVED
     */
    private static Map<String, Object> scenarioOfflineDropRecover(String tokenA, String tokenB) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", false);
        r.put("why", "验证离线链路：收件人离线落库DROPPED，上线后补发并ACK_RECEIVED推进状态");

        HttpClient httpClient = HttpClient.newHttpClient();
        QueueListener listenerA = new QueueListener();

        WebSocket wsA = null;
        try {
            wsA = connect(httpClient, tokenA, listenerA);
            auth(wsA, tokenA, listenerA.queue);

            String clientMsgId = "offline-" + UUID.randomUUID();
            sendSingleChat(wsA, clientMsgId, USER_B, "hello-offline");

            String ackSaved = await(listenerA.queue, TIMEOUT_MS, isAckSaved(clientMsgId));
            String serverMsgId = requireField(ackSaved, "serverMsgId");

            // 此时 B 仍未上线，按设计 DB 应为 DROPPED；我们不在 Java 里访问 DB，
            // 由 PowerShell 脚本用 mysql CLI 做校验并附加到输出中。

            // B 上线：AUTH 后应触发“DROPPED 拉取并推送”
            QueueListener listenerB = new QueueListener();
            WebSocket wsB = null;
            try {
                wsB = connect(httpClient, tokenB, listenerB);
                auth(wsB, tokenB, listenerB.queue);

                // 期待：收到之前那条离线消息
                await(listenerB.queue, TIMEOUT_MS, isSingleChat(clientMsgId, serverMsgId));

                // 收件人确认收到：回 ACK_RECEIVED（ack_receive）
                sendAckReceive(wsB, clientMsgId, serverMsgId, USER_A);
                Thread.sleep(800);

                r.put("ok", true);
                r.put("clientMsgId", clientMsgId);
                r.put("serverMsgId", serverMsgId);
                r.put("expected", "B离线时DB应为DROPPED(6)；B上线后补发，回ACK后DB应为RECEIVED(5)");
                return r;
            } finally {
                // 保证 B 侧连接关闭
                //noinspection Convert2MethodRef
                close(wsB);
            }
        } catch (Exception e) {
            r.put("reason", "常见原因：离线补发逻辑未启用（AUTH后未拉取DROPPED）、鉴权失败、超时未收到补发消息");
            r.put("error", String.valueOf(e));
            return r;
        } finally {
            close(wsA);
        }
    }

    private static WebSocket connect(HttpClient httpClient, String token, QueueListener listener) {
        // 注意：Java HttpClient 的 WebSocketBuilder 在某些环境不允许自定义握手 headers（但 JDK22 正常）
        // 如果握手返回 401，通常是：
        // - jwtSecret 不一致
        // - iat/exp 单位不一致（服务端要求秒，这里按秒生成）
        // - token 类型 typ 不是 access
        return httpClient.newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .buildAsync(URI.create(WS_URL), listener)
                .join();
    }

    private static void auth(WebSocket ws, String token, BlockingQueue<String> inbox) throws Exception {
        ws.sendText(jsonObj(
                "type", "AUTH",
                "token", token
        ), true).join();

        // 只要出现 AUTH_OK 即代表“已绑定 userId 且 token 解析成功”
        await(inbox, TIMEOUT_MS, s -> "AUTH_OK".equalsIgnoreCase(extractString(s, "type")));
    }

    private static void sendSingleChat(WebSocket wsA, String clientMsgId, long toUserId, String body) {
        wsA.sendText(jsonObj(
                "type", "SINGLE_CHAT",
                "clientMsgId", clientMsgId,
                "to", String.valueOf(toUserId),
                "msgType", "TEXT",
                "body", body,
                "ts", String.valueOf(Instant.now().toEpochMilli())
        ), true).join();
    }

    private static void sendAckReceive(WebSocket ws, String clientMsgId, String serverMsgId, long toUserId) {
        // ackType 支持 received / ack_receive（服务端做了 ignoreCase）
        ws.sendText(jsonObj(
                "type", "ACK",
                "clientMsgId", clientMsgId,
                "serverMsgId", serverMsgId,
                "to", String.valueOf(toUserId),
                "ackType", "ack_receive",
                "ts", String.valueOf(Instant.now().toEpochMilli())
        ), true).join();
    }

    private static Predicate<String> isAckSaved(String clientMsgId) {
        return s -> {
            if (!"ACK".equalsIgnoreCase(extractString(s, "type"))) return false;
            if (!clientMsgId.equals(extractString(s, "clientMsgId"))) return false;
            String ackType = extractString(s, "ackType");
            return ackType != null && "saved".equalsIgnoreCase(ackType);
        };
    }

    private static Predicate<String> isSingleChat(String clientMsgId, String serverMsgId) {
        return s -> {
            if (!"SINGLE_CHAT".equalsIgnoreCase(extractString(s, "type"))) return false;
            if (!clientMsgId.equals(extractString(s, "clientMsgId"))) return false;
            return serverMsgId.equals(extractString(s, "serverMsgId"));
        };
    }

    private static String requireField(String json, String field) {
        String v = extractString(json, field);
        if (v == null || v.isBlank()) {
            throw new RuntimeException("missing field in ws json: " + field);
        }
        return v;
    }

    private static void close(WebSocket ws) {
        if (ws == null) return;
        try {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        } catch (Exception ignored) {
        }
    }

    private static String issueAccessToken(long userId) {
        // 与服务端 JwtService 一致：iat/exp 为秒（Date.from(Instant)）
        long now = Instant.now().getEpochSecond();
        long exp = now + 1800;

        String headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payloadJson = "{"
                + "\"iss\":\"mini-im\","
                + "\"jti\":" + jsonString(UUID.randomUUID().toString()) + ","
                + "\"iat\":" + now + ","
                + "\"exp\":" + exp + ","
                + "\"uid\":" + userId + ","
                + "\"typ\":\"access\""
                + "}";

        String header = b64Url(headerJson.getBytes(StandardCharsets.UTF_8));
        String payload = b64Url(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + payload;
        String sig = b64Url(hmacSha256(signingInput.getBytes(StandardCharsets.US_ASCII), JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
        return signingInput + "." + sig;
    }

    private static byte[] hmacSha256(byte[] data, byte[] secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String b64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String jsonString(String s) {
        // 仅满足本脚本使用场景（token/clientMsgId/serverMsgId 不含控制字符）
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String jsonObj(String... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("kv length must be even");
        }
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            String k = kv[i];
            String v = kv[i + 1];
            sb.append(jsonString(k)).append(':');
            if (isJsonNumber(v)) {
                sb.append(v);
            } else {
                sb.append(jsonString(v));
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private static boolean isJsonNumber(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static String await(BlockingQueue<String> queue, long timeoutMs, Predicate<String> predicate) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            long remainingNs = deadline - System.nanoTime();
            if (remainingNs <= 0) {
                throw new RuntimeException("timeout waiting ws message");
            }
            String s = queue.poll(Math.min(200, TimeUnit.NANOSECONDS.toMillis(remainingNs)), TimeUnit.MILLISECONDS);
            if (s == null) {
                continue;
            }
            if (predicate.test(s)) {
                return s;
            }
        }
    }

    private static String extractString(String json, String field) {
        if (json == null || field == null) return null;
        String needle = "\"" + field + "\":";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int start = idx + needle.length();
        while (start < json.length() && (json.charAt(start) == ' ')) start++;
        if (start >= json.length() || json.charAt(start) != '"') return null;
        start++;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                sb.append(next);
                i++;
                continue;
            }
            sb.append(c);
        }
        return null;
    }

    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(jsonString(e.getKey())).append(':').append(toJsonValue(e.getValue()));
        }
        sb.append('}');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String toJsonValue(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean b) return b ? "true" : "false";
        if (v instanceof Number n) return String.valueOf(n);
        if (v instanceof String s) return jsonString(s);
        if (v instanceof Map<?, ?> m) return toJson((Map<String, Object>) m);
        return jsonString(String.valueOf(v));
    }

    static class QueueListener implements WebSocket.Listener {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                queue.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            queue.add("{\"type\":\"__ERROR__\",\"reason\":" + jsonString(String.valueOf(error)) + "}");
        }
    }
}
