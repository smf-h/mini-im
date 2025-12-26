import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
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
 * 单聊 WebSocket 冒烟测试（不依赖 Redis，不依赖 HTTP 登录接口）。
 *
 * 约定：
 * - 使用 application.yml 默认 jwt-secret 生成 accessToken（可通过 -DjwtSecret 覆盖）。
 * - 使用 HTTP Upgrade 阶段 Authorization: Bearer <token> 进行握手鉴权，然后再补发一帧 AUTH（兼容旧客户端逻辑）。
 *
 * 输出：一行 JSON（ok/clientMsgId/serverMsgId）
 */
public class WsSmokeTest {

    private static final String WS_URL = System.getProperty("ws", "ws://127.0.0.1:9001/ws");
    private static final String JWT_SECRET = System.getProperty("jwtSecret", "change-me-please-change-me-please-change-me");

    private static final long USER_A = Long.parseLong(System.getProperty("userA", "10001"));
    private static final long USER_B = Long.parseLong(System.getProperty("userB", "10002"));

    private static final long TIMEOUT_MS = Long.parseLong(System.getProperty("timeoutMs", "8000"));

    public static void main(String[] args) throws Exception {
        String tokenA = issueAccessToken(USER_A);
        String tokenB = issueAccessToken(USER_B);

        HttpClient httpClient = HttpClient.newHttpClient();

        QueueListener listenerA = new QueueListener();
        QueueListener listenerB = new QueueListener();

        WebSocket wsA = httpClient.newWebSocketBuilder()
                .header("Authorization", "Bearer " + tokenA)
                .buildAsync(URI.create(WS_URL), listenerA)
                .join();

        WebSocket wsB = httpClient.newWebSocketBuilder()
                .header("Authorization", "Bearer " + tokenB)
                .buildAsync(URI.create(WS_URL), listenerB)
                .join();

        wsA.sendText(jsonObj(
                "type", "AUTH",
                "token", tokenA
        ), true).join();

        wsB.sendText(jsonObj(
                "type", "AUTH",
                "token", tokenB
        ), true).join();

        await(listenerA.queue, TIMEOUT_MS, s -> "AUTH_OK".equalsIgnoreCase(extractString(s, "type")));
        await(listenerB.queue, TIMEOUT_MS, s -> "AUTH_OK".equalsIgnoreCase(extractString(s, "type")));

        String clientMsgId = "c1-" + UUID.randomUUID();

        wsA.sendText(jsonObj(
                "type", "SINGLE_CHAT",
                "clientMsgId", clientMsgId,
                "to", String.valueOf(USER_B),
                "msgType", "TEXT",
                "body", "hello",
                "ts", String.valueOf(Instant.now().toEpochMilli())
        ), true).join();

        String ackSaved = await(listenerA.queue, TIMEOUT_MS, s -> {
            if (!"ACK".equalsIgnoreCase(extractString(s, "type"))) return false;
            if (!clientMsgId.equals(extractString(s, "clientMsgId"))) return false;
            String ackType = extractString(s, "ackType");
            return ackType != null && "saved".equalsIgnoreCase(ackType);
        });

        String serverMsgId = Objects.requireNonNull(extractString(ackSaved, "serverMsgId"), "missing serverMsgId in ACK");

        await(listenerB.queue, TIMEOUT_MS, s -> {
            if (!"SINGLE_CHAT".equalsIgnoreCase(extractString(s, "type"))) return false;
            if (!clientMsgId.equals(extractString(s, "clientMsgId"))) return false;
            return serverMsgId.equals(extractString(s, "serverMsgId"));
        });

        wsB.sendText(jsonObj(
                "type", "ACK",
                "clientMsgId", clientMsgId,
                "serverMsgId", serverMsgId,
                "to", String.valueOf(USER_A),
                "ackType", "ack_receive",
                "ts", String.valueOf(Instant.now().toEpochMilli())
        ), true).join();

        // 给服务端异步 DB 更新一点时间
        Thread.sleep(800);

        System.out.println("{\"ok\":true,\"clientMsgId\":" + jsonString(clientMsgId) + ",\"serverMsgId\":" + jsonString(serverMsgId) + "}");

        wsA.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        wsB.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
    }

    private static String issueAccessToken(long userId) {
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

