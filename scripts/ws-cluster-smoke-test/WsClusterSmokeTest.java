import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * WS 多实例/多端冒烟（脚本级验证）。
 *
 * 目标覆盖：
 * - 两实例：A 连到 wsA，B 连到 wsB
 * - 路由：跨实例 PUSH（SINGLE_CHAT 与 GROUP_CHAT/GROUP_NOTIFY）
 * - 顺序：同一连接连续发送两条消息，对端到达顺序不乱
 * - 群策略：auto/push/notify/none（notify 会走 /group/message/since 拉取增量）
 *
 * 运行参数（通过 -Dxxx 传入）：
 * -DwsA=ws://127.0.0.1:9001/ws
 * -DwsB=ws://127.0.0.1:9002/ws
 * -DhttpA=http://127.0.0.1:8080
 * -DhttpB=http://127.0.0.1:8082
 * -DuserAName=cluster_a -DuserBName=cluster_b -Dpassword=p
 * -DtimeoutMs=12000
 * -DgroupMode=auto|push|notify|none
 */
public class WsClusterSmokeTest {

    private static final String WS_A = System.getProperty("wsA", "ws://127.0.0.1:9001/ws");
    private static final String WS_B = System.getProperty("wsB", "ws://127.0.0.1:9002/ws");
    private static final String HTTP_A = stripSlash(System.getProperty("httpA", "http://127.0.0.1:8080"));
    private static final String HTTP_B = stripSlash(System.getProperty("httpB", "http://127.0.0.1:8082"));
    private static final String USER_A_NAME = System.getProperty("userAName", "cluster_a");
    private static final String USER_B_NAME = System.getProperty("userBName", "cluster_b");
    private static final String PASSWORD = System.getProperty("password", "p");
    private static final long TIMEOUT_MS = Long.parseLong(System.getProperty("timeoutMs", "12000"));
    private static final String GROUP_MODE = System.getProperty("groupMode", "auto").trim().toLowerCase();
    private static final boolean MULTI_DEVICE = Boolean.parseBoolean(System.getProperty("multiDevice", "true"));

    public static void main(String[] args) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("wsA", WS_A);
        vars.put("wsB", WS_B);
        vars.put("httpA", HTTP_A);
        vars.put("httpB", HTTP_B);
        vars.put("userAName", USER_A_NAME);
        vars.put("userBName", USER_B_NAME);
        vars.put("timeoutMs", TIMEOUT_MS);
        vars.put("groupMode", GROUP_MODE);
        out.put("vars", vars);

        List<Map<String, Object>> steps = new ArrayList<>();
        out.put("steps", steps);

        HttpClient http = HttpClient.newHttpClient();

        // 1) login (auto register)
        Login a = login(http, HTTP_A, USER_A_NAME, PASSWORD);
        Login b = login(http, HTTP_B, USER_B_NAME, PASSWORD);
        steps.add(step("A->HTTP", "login", maskTokenInRaw(a.raw)));
        steps.add(step("B->HTTP", "login", maskTokenInRaw(b.raw)));

        // 1.1) multi-device/single-login smoke (optional; disable by -DmultiDevice=false)
        // login again to bump sessionVersion, then expect old WS to be kicked by the new WS auth.
        if (MULTI_DEVICE) {
            QueueListener listenerKickA = new QueueListener();
            QueueListener listenerKickB = new QueueListener();
            WebSocket wsKickA = null;
            WebSocket wsKickB = null;
            try {
                wsKickA = connectPreferHeaderThenQuery(http, WS_A, a.accessToken, listenerKickA);
                auth(wsKickA, a.accessToken, listenerKickA.queue);

                Login a2 = login(http, HTTP_B, USER_A_NAME, PASSWORD);
                steps.add(step("A2->HTTP", "login (bump sv)", maskTokenInRaw(a2.raw)));

                wsKickB = connectPreferHeaderThenQuery(http, WS_B, a2.accessToken, listenerKickB);
                auth(wsKickB, a2.accessToken, listenerKickB.queue);

                String kicked = await(listenerKickA.queue, TIMEOUT_MS, isErrorReason("kicked"));
                steps.add(step("S->A", "KICK (single-login)", kicked));

                // continue the rest of the test using the latest token
                a = a2;
            } finally {
                if (wsKickA != null) wsKickA.abort();
                if (wsKickB != null) wsKickB.abort();
            }
        }

        // 2) create group & accept join (so that group chat is meaningful)
        String groupId = createGroup(http, HTTP_A, a.accessToken, "cluster-" + UUID.randomUUID().toString().substring(0, 8));
        steps.add(step("A->HTTP", "group/create", "{\"groupId\":" + jsonString(groupId) + "}"));

        String groupCode = groupProfileCode(http, HTTP_A, a.accessToken, groupId);
        steps.add(step("A->HTTP", "group/profile/by-id", "{\"groupCode\":" + jsonString(groupCode) + "}"));

        String joinRequestId = joinGroup(http, HTTP_B, b.accessToken, groupCode);
        steps.add(step("B->HTTP", "group/join/request", "{\"requestId\":" + jsonString(joinRequestId) + "}"));

        String pendingId = findPendingJoinRequestId(http, HTTP_A, a.accessToken, groupId);
        steps.add(step("A->HTTP", "group/join/requests", "{\"pendingId\":" + jsonString(pendingId) + "}"));

        decideJoin(http, HTTP_A, a.accessToken, pendingId, "accept");
        steps.add(step("A->HTTP", "group/join/decide", "{\"action\":\"accept\"}"));

        // 3) connect A(wsA) and B(wsB)
        QueueListener listenerA = new QueueListener();
        QueueListener listenerB = new QueueListener();

        WebSocket wsA = null;
        WebSocket wsB = null;
        try {
            wsA = connectPreferHeaderThenQuery(http, WS_A, a.accessToken, listenerA);
            wsB = connectPreferHeaderThenQuery(http, WS_B, b.accessToken, listenerB);
            steps.add(step("A->S", "AUTH", authJsonMasked(a.accessToken)));
            auth(wsA, a.accessToken, listenerA.queue);
            steps.add(step("B->S", "AUTH", authJsonMasked(b.accessToken)));
            auth(wsB, b.accessToken, listenerB.queue);

            // 4) cross-instance single chat (order)
            String sClient1 = "s1-" + UUID.randomUUID();
            String sClient2 = "s2-" + UUID.randomUUID();
            String sBody1 = "hello-1-" + UUID.randomUUID().toString().substring(0, 6);
            String sBody2 = "hello-2-" + UUID.randomUUID().toString().substring(0, 6);

            sendText(wsA, jsonObj(
                    "type", "SINGLE_CHAT",
                    "clientMsgId", sClient1,
                    "to", b.userId,
                    "body", sBody1,
                    "msgType", "TEXT",
                    "ts", String.valueOf(Instant.now().toEpochMilli())
            ));
            sendText(wsA, jsonObj(
                    "type", "SINGLE_CHAT",
                    "clientMsgId", sClient2,
                    "to", b.userId,
                    "body", sBody2,
                    "msgType", "TEXT",
                    "ts", String.valueOf(Instant.now().toEpochMilli())
            ));
            steps.add(step("A->S", "SINGLE_CHAT x2", "{\"clientMsgId1\":" + jsonString(sClient1) + ",\"clientMsgId2\":" + jsonString(sClient2) + "}"));

            String bMsg1 = await(listenerB.queue, TIMEOUT_MS, isSingleChatBody(sBody1));
            String bMsg2 = await(listenerB.queue, TIMEOUT_MS, isSingleChatBody(sBody2));
            steps.add(step("S->B", "SINGLE_CHAT #1", bMsg1));
            steps.add(step("S->B", "SINGLE_CHAT #2", bMsg2));

            // 5) group chat: depends on mode (auto/push/notify/none)
            // override by restarting servers with --im.group-chat.strategy.mode=... before running this script.
            String gClient = "g1-" + UUID.randomUUID();
            String gBody = "group-hello-" + UUID.randomUUID().toString().substring(0, 6);

            sendText(wsA, jsonObj(
                    "type", "GROUP_CHAT",
                    "clientMsgId", gClient,
                    "groupId", groupId,
                    "body", gBody,
                    "msgType", "TEXT",
                    "ts", String.valueOf(Instant.now().toEpochMilli())
            ));
            steps.add(step("A->S", "GROUP_CHAT send", "{\"groupId\":" + jsonString(groupId) + ",\"clientMsgId\":" + jsonString(gClient) + "}"));

            if ("none".equals(GROUP_MODE)) {
                // expect nothing to B (best-effort)
                out.put("ok", true);
                out.put("note", "groupMode=none, skip group receive assertion");
                System.out.println(toJson(out));
                return;
            }

            String gAny = await(listenerB.queue, TIMEOUT_MS, s -> {
                String type = extractString(s, "type");
                if ("GROUP_CHAT".equalsIgnoreCase(type)) {
                    if (!Objects.equals(groupId, extractString(s, "groupId"))) return false;
                    return Objects.equals(gBody, extractString(s, "body"));
                }
                if ("GROUP_NOTIFY".equalsIgnoreCase(type)) {
                    return Objects.equals(groupId, extractString(s, "groupId"));
                }
                return false;
            });
            steps.add(step("S->B", "GROUP_* received", gAny));

            String gType = extractString(gAny, "type");
            out.put("groupReceivedType", gType);

            if ("push".equals(GROUP_MODE) && !"GROUP_CHAT".equalsIgnoreCase(gType)) {
                throw new RuntimeException("expected GROUP_CHAT but got " + gType);
            }
            if ("notify".equals(GROUP_MODE) && !"GROUP_NOTIFY".equalsIgnoreCase(gType)) {
                throw new RuntimeException("expected GROUP_NOTIFY but got " + gType);
            }

            if ("GROUP_NOTIFY".equalsIgnoreCase(gType)) {
                String notifyServerMsgId = extractString(gAny, "serverMsgId");
                if (notifyServerMsgId == null || notifyServerMsgId.isBlank()) {
                    throw new RuntimeException("GROUP_NOTIFY missing serverMsgId");
                }
                String notifyMsgSeq = extractString(gAny, "msgSeq");
                if (notifyMsgSeq == null || notifyMsgSeq.isBlank()) {
                    throw new RuntimeException("GROUP_NOTIFY missing msgSeq");
                }
                long sinceSeq = parseLongOrZero(notifyMsgSeq) - 1;
                if (sinceSeq < 0) sinceSeq = 0;
                String pulled = pullGroupSince(http, HTTP_B, b.accessToken, groupId, sinceSeq);
                steps.add(step("B->HTTP", "group/message/since", pulled));
                if (!pulled.contains(notifyServerMsgId)) {
                    throw new RuntimeException("since did not include serverMsgId=" + notifyServerMsgId);
                }
                if (!pulled.contains(gBody)) {
                    throw new RuntimeException("since did not include expected body");
                }
            } else if ("GROUP_CHAT".equalsIgnoreCase(gType)) {
                // in push mode we at least ensure the body matches
                String body = extractString(gAny, "body");
                if (!Objects.equals(gBody, body)) {
                    throw new RuntimeException("GROUP_CHAT body mismatch");
                }
            }

            out.put("ok", true);
        } finally {
            close(wsA);
            close(wsB);
        }

        System.out.println(toJson(out));
    }

    private static Login login(HttpClient http, String base, String username, String password) throws Exception {
        String body = "{\"username\":" + jsonString(username) + ",\"password\":" + jsonString(password) + "}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base + "/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String raw = resp.body() == null ? "" : resp.body();
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("login http status=" + resp.statusCode() + ", body=" + raw);
        }
        if (!extractBoolean(raw, "ok")) {
            throw new RuntimeException("login failed: " + raw);
        }
        String token = requireField(raw, "accessToken");
        String userId = requireField(raw, "userId");
        return new Login(userId, token, raw);
    }

    private static String createGroup(HttpClient http, String base, String token, String name) throws Exception {
        String body = "{\"name\":" + jsonString(name) + ",\"memberUserIds\":[]}";
        String raw = httpJson(http, base + "/group/create", token, body);
        return requireField(raw, "groupId");
    }

    private static String groupProfileCode(HttpClient http, String base, String token, String groupId) throws Exception {
        String url = base + "/group/profile/by-id?groupId=" + urlEncode(groupId);
        String raw = httpGet(http, url, token);
        if (!extractBoolean(raw, "ok")) {
            throw new RuntimeException("profile failed: " + raw);
        }
        return requireField(raw, "groupCode");
    }

    private static String joinGroup(HttpClient http, String base, String token, String groupCode) throws Exception {
        String body = "{\"groupCode\":" + jsonString(groupCode) + ",\"message\":\"hi\"}";
        String raw = httpJson(http, base + "/group/join/request", token, body);
        return requireField(raw, "requestId");
    }

    private static String findPendingJoinRequestId(HttpClient http, String base, String token, String groupId) throws Exception {
        String url = base + "/group/join/requests?groupId=" + urlEncode(groupId) + "&status=pending&limit=20";
        String raw = httpGet(http, url, token);
        if (!extractBoolean(raw, "ok")) {
            throw new RuntimeException("list join requests failed: " + raw);
        }
        // data is a list; pick first "id"
        String id = extractString(raw, "id");
        if (id == null || id.isBlank()) {
            throw new RuntimeException("no pending join request id in response: " + raw);
        }
        return id;
    }

    private static void decideJoin(HttpClient http, String base, String token, String requestId, String action) throws Exception {
        String body = "{\"requestId\":" + requestId + ",\"action\":" + jsonString(action) + "}";
        String raw = httpJson(http, base + "/group/join/decide", token, body);
        if (!extractBoolean(raw, "ok")) {
            throw new RuntimeException("decide join failed: " + raw);
        }
    }

    private static String pullGroupSince(HttpClient http, String base, String token, String groupId, long sinceSeq) throws Exception {
        String url = base + "/group/message/since?groupId=" + urlEncode(groupId) + "&limit=50&sinceSeq=" + sinceSeq;
        return httpGet(http, url, token);
    }

    private static WebSocket connectPreferHeaderThenQuery(HttpClient httpClient, String wsUrl, String token, QueueListener listener) {
        // AUTH-first：握手阶段不再做鉴权；token 只在首包 AUTH 里传。
        return httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), listener)
                .orTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .join();
    }

    private static void auth(WebSocket ws, String token, BlockingQueue<String> inbox) throws Exception {
        sendText(ws, jsonObj("type", "AUTH", "token", token));
        await(inbox, TIMEOUT_MS, s -> "AUTH_OK".equalsIgnoreCase(extractString(s, "type")));
    }

    private static void sendText(WebSocket ws, String json) {
        ws.sendText(json, true).orTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).join();
    }

    private static void close(WebSocket ws) {
        if (ws == null) return;
        try {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").orTimeout(800, TimeUnit.MILLISECONDS).join();
        } catch (Exception ignore) {
        }
    }

    private static Predicate<String> isSingleChatBody(String body) {
        return s -> "SINGLE_CHAT".equalsIgnoreCase(extractString(s, "type")) && Objects.equals(body, extractString(s, "body"));
    }

    private static Predicate<String> isErrorReason(String expectedReason) {
        return s -> "ERROR".equalsIgnoreCase(extractString(s, "type")) && Objects.equals(expectedReason, extractString(s, "reason"));
    }

    private static String httpJson(HttpClient http, String url, String token, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String raw = resp.body() == null ? "" : resp.body();
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("http status=" + resp.statusCode() + ", url=" + url + ", body=" + raw);
        }
        if (!extractBoolean(raw, "ok")) {
            throw new RuntimeException("http failed: url=" + url + ", body=" + raw);
        }
        return raw;
    }

    private static String httpGet(HttpClient http, String url, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String raw = resp.body() == null ? "" : resp.body();
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("http status=" + resp.statusCode() + ", url=" + url + ", body=" + raw);
        }
        return raw;
    }

    private static String authJsonMasked(String token) {
        return jsonObj("type", "AUTH", "token", maskToken(token));
    }

    private static String maskTokenInRaw(String raw) {
        if (raw == null) return null;
        // naive: replace token value with masked
        String token = extractString(raw, "accessToken");
        if (token == null) return raw;
        return raw.replace(token, maskToken(token));
    }

    private static String maskToken(String token) {
        if (token == null) return null;
        int len = token.length();
        if (len <= 12) return "***";
        return token.substring(0, 6) + "..." + token.substring(len - 4);
    }

    private static String await(BlockingQueue<String> inbox, long timeoutMs, Predicate<String> filter) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String s = inbox.poll(50, TimeUnit.MILLISECONDS);
            if (s == null) continue;
            if (filter.test(s)) return s;
        }
        throw new RuntimeException("timeout waiting message");
    }

    private static String requireField(String json, String field) {
        String s = extractString(json, field);
        if (s == null) throw new IllegalArgumentException("missing field: " + field);
        return s;
    }

    private static boolean extractBoolean(String json, String field) {
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) return false;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return false;
        String tail = json.substring(colon + 1).trim();
        return tail.startsWith("true");
    }

    private static String extractString(String json, String field) {
        if (json == null) return null;
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) return null;
        char c = json.charAt(i);
        if (c == '"') {
            int j = i + 1;
            StringBuilder sb = new StringBuilder();
            while (j < json.length()) {
                char ch = json.charAt(j);
                if (ch == '\\') {
                    if (j + 1 < json.length()) {
                        char next = json.charAt(j + 1);
                        sb.append(next);
                        j += 2;
                        continue;
                    }
                }
                if (ch == '"') break;
                sb.append(ch);
                j++;
            }
            return sb.toString();
        }
        // number or null
        int j = i;
        while (j < json.length()) {
            char ch = json.charAt(j);
            if (ch == ',' || ch == '}' || Character.isWhitespace(ch)) break;
            j++;
        }
        String raw = json.substring(i, j).trim();
        if ("null".equals(raw)) return null;
        return raw;
    }

    private static long parseLongOrZero(String raw) {
        if (raw == null) return 0;
        String s = raw.trim();
        if (s.isEmpty()) return 0;
        try {
            return Long.parseLong(s);
        } catch (Exception ignore) {
            return 0;
        }
    }

    private static String jsonObj(String... kv) {
        if (kv == null || kv.length == 0) return "{}";
        if (kv.length % 2 != 0) throw new IllegalArgumentException("kv must be even");
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append(jsonString(kv[i]));
            sb.append(':');
            sb.append(jsonValue(kv[i + 1]));
        }
        sb.append('}');
        return sb.toString();
    }

    private static String jsonValue(String v) {
        if (v == null) return "null";
        // treat numbers/bools already encoded by caller
        if (isJsonNumber(v) || "true".equals(v) || "false".equals(v)) return v;
        return jsonString(v);
    }

    private static boolean isJsonNumber(String s) {
        if (s == null) return false;
        String v = s.trim();
        if (v.isEmpty()) return false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '-' && i == 0) continue;
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '"') sb.append('\\');
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(String.valueOf(s), StandardCharsets.UTF_8);
    }

    private static String stripSlash(String s) {
        if (s == null) return "";
        return s.replaceAll("/+$", "");
    }

    private static Map<String, Object> step(String dir, String name, String raw) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dir", dir);
        m.put("name", name);
        m.put("raw", raw);
        return m;
    }

    private static String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String s) return jsonString(s);
        if (obj instanceof Boolean b) return b ? "true" : "false";
        if (obj instanceof Number n) return String.valueOf(n);
        if (obj instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(jsonString(String.valueOf(e.getKey())));
                sb.append(':');
                sb.append(toJson(e.getValue()));
            }
            sb.append('}');
            return sb.toString();
        }
        if (obj instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(toJson(list.get(i)));
            }
            sb.append(']');
            return sb.toString();
        }
        return jsonString(String.valueOf(obj));
    }

    private record Login(String userId, String accessToken, String raw) {
    }

    private static class QueueListener implements WebSocket.Listener {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            queue.offer(String.valueOf(data));
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
    }
}
