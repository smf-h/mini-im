import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class WsGroupLoadTest {

    private static final class Args {
        List<String> wsUrls = List.of("ws://127.0.0.1:9001/ws");
        String httpBase = "http://127.0.0.1:8080";
        int clients = 200;
        int senders = 20;
        int durationSeconds = 60;
        int warmupMs = 1500;
        int msgIntervalMs = 50;
        String userPrefix = "glt";
        String password = "p";
        int receiverSamplePct = 30;
        int bodyBytes = 0;
    }

    private static final class Metrics {
        final AtomicLong httpLoginOk = new AtomicLong();
        final AtomicLong httpLoginFail = new AtomicLong();
        final AtomicLong wsConnectOk = new AtomicLong();
        final AtomicLong wsConnectFail = new AtomicLong();
        final AtomicLong authOk = new AtomicLong();
        final AtomicLong authFail = new AtomicLong();

        final AtomicLong msgSent = new AtomicLong();
        final AtomicLong ackSaved = new AtomicLong();
        final AtomicLong msgRecv = new AtomicLong();
        final AtomicLong msgRecvUnique = new AtomicLong();
        final AtomicLong msgDup = new AtomicLong();
        final AtomicLong reorderByFrom = new AtomicLong();
        final AtomicLong reorderByServerMsgId = new AtomicLong();
        final ConcurrentLinkedQueue<Long> e2eLatencyMs = new ConcurrentLinkedQueue<>();
        final AtomicLong wsError = new AtomicLong();
        final LongAdder wsErrorSendFail = new LongAdder();
        final ConcurrentHashMap<String, LongAdder> wsErrorByReason = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<String> wsErrorSamples = new ConcurrentLinkedQueue<>();
        final AtomicInteger wsErrorSampleSize = new AtomicInteger(0);
        final int wsErrorSampleMax = 5;
        final AtomicBoolean stopping = new AtomicBoolean(false);

        final AtomicBoolean dupCapped = new AtomicBoolean(false);
    }

    private static final class Login {
        final long userId;
        final String accessToken;

        Login(long userId, String accessToken) {
            this.userId = userId;
            this.accessToken = accessToken;
        }
    }

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("glt-scheduler");
        return t;
    });

    private static final Random rnd = new Random(1);

    private static final class ClientCtx implements WebSocket.Listener {
        final Args args;
        final Metrics metrics;
        final HttpClient http;
        final long userId;
        final String token;
        final boolean sender;
        final boolean sampleReceiver;
        final long groupId;

        final AtomicBoolean authed = new AtomicBoolean(false);
        final AtomicBoolean closed = new AtomicBoolean(false);
        final AtomicLong sendSeq = new AtomicLong(0);
        final Map<Long, Long> lastSeqByFrom = new ConcurrentHashMap<>();
        final AtomicLong lastServerMsgId = new AtomicLong(0);
        final Set<String> seenClientMsgId = ConcurrentHashMap.newKeySet();
        final AtomicBoolean seenCapped = new AtomicBoolean(false);
        final AtomicInteger seenSize = new AtomicInteger(0);
        final int seenMax = 100_000;

        volatile WebSocket ws;
        private final StringBuilder textBuf = new StringBuilder();

        ClientCtx(Args args, Metrics metrics, HttpClient http, long userId, String token, boolean sender, boolean sampleReceiver, long groupId) {
            this.args = args;
            this.metrics = metrics;
            this.http = http;
            this.userId = userId;
            this.token = token;
            this.sender = sender;
            this.sampleReceiver = sampleReceiver;
            this.groupId = groupId;
        }

        void connect() {
            String wsUrl = pickWsUrl(args.wsUrls, userId);
            WebSocket socket;
            try {
                socket = connectPreferHeaderThenQuery(http, wsUrl, token, this);
            } catch (Exception e) {
                metrics.wsConnectFail.incrementAndGet();
                return;
            }
            ws = socket;
            metrics.wsConnectOk.incrementAndGet();
            socket.sendText("{\"type\":\"AUTH\",\"token\":\"" + token + "\"}", true);
        }

        void close() {
            closed.set(true);
            WebSocket socket = ws;
            if (socket != null) {
                try {
                    socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
                } catch (Exception ignored) {
                }
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (data != null) {
                textBuf.append(data);
            }
            if (!last) {
                webSocket.request(1);
                return null;
            }
            String s = textBuf.toString();
            textBuf.setLength(0);
            Map<String, Object> msg = parseJsonShallow(s);
            String type = asString(msg.get("type"));
            if ("AUTH_OK".equals(type)) {
                authed.set(true);
                metrics.authOk.incrementAndGet();
            } else if ("AUTH_FAIL".equals(type)) {
                metrics.authFail.incrementAndGet();
            } else if ("ERROR".equals(type)) {
                if (metrics.stopping.get()) {
                    webSocket.request(1);
                    return null;
                }
                metrics.wsError.incrementAndGet();
                String reason = asString(msg.get("reason"));
                if (reason == null || reason.isBlank()) {
                    reason = "unknown";
                }
                metrics.wsErrorByReason.computeIfAbsent(reason, k -> new LongAdder()).increment();
                if (metrics.wsErrorSampleSize.get() < metrics.wsErrorSampleMax) {
                    if (metrics.wsErrorSampleSize.incrementAndGet() <= metrics.wsErrorSampleMax) {
                        metrics.wsErrorSamples.add(s);
                    }
                }
            } else if ("ACK".equals(type)) {
                String ackType = asString(msg.get("ackType"));
                if ("saved".equals(ackType)) {
                    metrics.ackSaved.incrementAndGet();
                }
            } else if ("GROUP_CHAT".equals(type)) {
                metrics.msgRecv.incrementAndGet();
                if (!sampleReceiver) {
                    webSocket.request(1);
                    return null;
                }

                String clientMsgId = asString(msg.get("clientMsgId"));
                if (clientMsgId != null && !clientMsgId.isBlank()) {
                    if (!trackUniqueLocal(clientMsgId)) {
                        metrics.msgDup.incrementAndGet();
                    } else {
                        metrics.msgRecvUnique.incrementAndGet();
                    }
                }

                Long serverMsgId = asLong(msg.get("serverMsgId"));
                if (serverMsgId != null) {
                    long prev = lastServerMsgId.get();
                    if (serverMsgId <= prev) {
                        metrics.reorderByServerMsgId.incrementAndGet();
                    } else {
                        lastServerMsgId.set(serverMsgId);
                    }
                }

                Long from = asLong(msg.get("from"));
                String body = asString(msg.get("body"));
                if (body != null) {
                    Map<String, Object> payload = parseJsonShallow(body);
                    Long sendTs = asLong(payload.get("sendTs"));
                    if (sendTs != null) {
                        metrics.e2eLatencyMs.add(System.currentTimeMillis() - sendTs);
                    }
                    Long seq = asLong(payload.get("seq"));
                    if (seq != null && from != null) {
                        long lastSeq = lastSeqByFrom.getOrDefault(from, 0L);
                        if (seq <= lastSeq) {
                            metrics.reorderByFrom.incrementAndGet();
                        } else {
                            lastSeqByFrom.put(from, seq);
                        }
                    }
                }
            }

            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            authed.set(false);
            if (metrics.stopping.get()) {
                return null;
            }
            // Count abnormal closes as wsError (but keep it explainable via errorsByReason).
            if (statusCode != 1000) {
                metrics.wsError.incrementAndGet();
                String bucket = "close_" + statusCode;
                metrics.wsErrorByReason.computeIfAbsent(bucket, k -> new LongAdder()).increment();
                if (metrics.wsErrorSampleSize.get() < metrics.wsErrorSampleMax) {
                    if (metrics.wsErrorSampleSize.incrementAndGet() <= metrics.wsErrorSampleMax) {
                        metrics.wsErrorSamples.add("onClose status=" + statusCode + ", reason=" + reason);
                    }
                }
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            authed.set(false);
            if (metrics.stopping.get()) {
                return;
            }
            metrics.wsError.incrementAndGet();
            metrics.wsErrorByReason.computeIfAbsent("on_error", k -> new LongAdder()).increment();
            if (metrics.wsErrorSampleSize.get() < metrics.wsErrorSampleMax) {
                if (metrics.wsErrorSampleSize.incrementAndGet() <= metrics.wsErrorSampleMax) {
                    metrics.wsErrorSamples.add("onError " + (error == null ? "null" : error.toString()));
                }
            }
        }

        private boolean trackUniqueLocal(String clientMsgId) {
            if (seenCapped.get()) {
                return true;
            }
            boolean added = seenClientMsgId.add(clientMsgId);
                if (added) {
                    int sz = seenSize.incrementAndGet();
                    if (sz > seenMax) {
                        seenCapped.set(true);
                        metrics.dupCapped.set(true);
                    }
                }
                return added;
        }
    }

    public static void main(String[] argv) throws Exception {
        Args args = parseArgs(argv);
        Metrics m = new Metrics();
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newFixedThreadPool(Math.min(64, Math.max(8, args.clients / 50))))
                .build();

        if (args.senders <= 0) {
            throw new IllegalArgumentException("--senders must be > 0");
        }
        if (args.senders >= args.clients) {
            throw new IllegalArgumentException("--senders must be < clients");
        }

        String base = stripSlash(args.httpBase);

        // 1) login/register users
        List<Login> users = new ArrayList<>(args.clients);
        for (int i = 0; i < args.clients; i++) {
            String username = args.userPrefix + "_" + i;
            try {
                Login l = login(http, base, username, args.password);
                users.add(l);
                m.httpLoginOk.incrementAndGet();
            } catch (Exception e) {
                m.httpLoginFail.incrementAndGet();
                throw e;
            }
        }

        // 2) create group + add members via join/decide
        Login owner = users.get(0);
        String groupIdStr = createGroup(http, base, owner.accessToken, "glt-" + Instant.now().toEpochMilli());
        long groupId = Long.parseLong(groupIdStr);
        String groupCode = groupProfileCode(http, base, owner.accessToken, groupIdStr);
        for (int i = 1; i < users.size(); i++) {
            Login u = users.get(i);
            String reqId = joinGroup(http, base, u.accessToken, groupCode);
            decideJoin(http, base, owner.accessToken, reqId, "accept");
        }

        // 3) connect ws
        List<ClientCtx> clients = new ArrayList<>(args.clients);
        for (int i = 0; i < users.size(); i++) {
            Login u = users.get(i);
            boolean sender = i < args.senders;
            boolean sampleRecv = !sender && rnd.nextInt(100) < args.receiverSamplePct;
            ClientCtx c = new ClientCtx(args, m, http, u.userId, u.accessToken, sender, sampleRecv, groupId);
            clients.add(c);
            c.connect();
        }

        Thread.sleep(args.warmupMs);

        long startMs = System.currentTimeMillis();
        long endMs = startMs + args.durationSeconds * 1000L;

        // 4) send loop
        scheduler.scheduleAtFixedRate(() -> {
            for (ClientCtx c : clients) {
                if (!c.sender) continue;
                if (!c.authed.get()) continue;
                WebSocket ws = c.ws;
                if (ws == null) continue;

                long seq = c.sendSeq.incrementAndGet();
                String clientMsgId = c.userId + "-" + seq;
                long sendTs = System.currentTimeMillis();
                String body = buildBody(sendTs, seq, args.bodyBytes);

                String msg = new StringJoiner(",", "{", "}")
                        .add("\"type\":\"GROUP_CHAT\"")
                        .add("\"groupId\":" + c.groupId)
                        .add("\"clientMsgId\":\"" + clientMsgId + "\"")
                        .add("\"msgType\":\"TEXT\"")
                        .add("\"body\":\"" + escapeJson(body) + "\"")
                        .add("\"ts\":" + sendTs)
                        .toString();

                m.msgSent.incrementAndGet();
                ws.sendText(msg, true).whenComplete((ignored, err) -> {
                    if (err != null) {
                        if (m.stopping.get()) {
                            return;
                        }
                        m.wsError.incrementAndGet();
                        m.wsErrorSendFail.increment();
                        m.wsErrorByReason.computeIfAbsent("send_fail", k -> new LongAdder()).increment();
                    }
                });
            }
        }, 0, args.msgIntervalMs, TimeUnit.MILLISECONDS);

        while (System.currentTimeMillis() < endMs) {
            Thread.sleep(1000);
        }

        m.stopping.set(true);
        for (ClientCtx c : clients) c.close();
        scheduler.shutdownNow();
        http.executor().ifPresent(e -> ((java.util.concurrent.ExecutorService) e).shutdownNow());

        printSummary(args, m, groupIdStr);
    }

    private static void printSummary(Args args, Metrics m, String groupId) {
        double durSec = Math.max(1.0, args.durationSeconds);
        List<Long> e2e = new ArrayList<>(m.e2eLatencyMs);
        Collections.sort(e2e);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "GROUP_CHAT_E2E");
        out.put("wsUrls", String.join(";", args.wsUrls));
        out.put("httpBase", stripSlash(args.httpBase));
        out.put("clients", args.clients);
        out.put("senders", args.senders);
        out.put("durationSeconds", args.durationSeconds);
        out.put("msgIntervalMs", args.msgIntervalMs);
        out.put("receiverSamplePct", args.receiverSamplePct);
        out.put("groupId", groupId);
        out.put("http", Map.of("loginOk", m.httpLoginOk.get(), "loginFail", m.httpLoginFail.get()));
        out.put("connect", Map.of("ok", m.wsConnectOk.get(), "fail", m.wsConnectFail.get()));
        out.put("auth", Map.of("ok", m.authOk.get(), "fail", m.authFail.get()));
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("sent", m.msgSent.get());
        g.put("sentPerSec", round(m.msgSent.get() / durSec));
        g.put("ackSaved", m.ackSaved.get());
        g.put("recv", m.msgRecv.get());
        g.put("recvUnique", m.msgRecvUnique.get());
        g.put("dup", m.msgDup.get());
        g.put("dupCapped", m.dupCapped.get());
        g.put("reorderByFrom", m.reorderByFrom.get());
        g.put("reorderByServerMsgId", m.reorderByServerMsgId.get());
        g.put("e2eMs", pctJson(e2e));
        out.put("groupChat", g);
        Map<String, Object> errors = new LinkedHashMap<>();
        errors.put("wsError", m.wsError.get());
        errors.put("sendFail", m.wsErrorSendFail.sum());
        errors.put("errorsByReason", toPlainCountMap(m.wsErrorByReason));
        errors.put("samples", new ArrayList<>(m.wsErrorSamples));
        out.put("errors", errors);
        System.out.println(toJson(out));
    }

    private static Map<String, Object> toPlainCountMap(ConcurrentHashMap<String, LongAdder> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (map == null || map.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, LongAdder> e : map.entrySet()) {
            if (e.getKey() == null) continue;
            long v = e.getValue() == null ? 0L : e.getValue().sum();
            if (v <= 0) continue;
            out.put(e.getKey(), v);
        }
        return out;
    }

    private static String pickWsUrl(List<String> urls, long userId) {
        if (urls == null || urls.isEmpty()) return "ws://127.0.0.1:9001/ws";
        int idx = (int) (Math.abs(userId) % urls.size());
        return urls.get(idx);
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
        return new Login(Long.parseLong(userId), token);
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

    private static void decideJoin(HttpClient http, String base, String token, String requestId, String action) throws Exception {
        String body = "{\"requestId\":" + requestId + ",\"action\":" + jsonString(action) + "}";
        String raw = httpJson(http, base + "/group/join/decide", token, body);
        if (!extractBoolean(raw, "ok")) {
            throw new RuntimeException("decide join failed: " + raw);
        }
    }

    private static WebSocket connectPreferHeaderThenQuery(HttpClient httpClient, String wsUrl, String token, WebSocket.Listener listener) throws Exception {
        try {
            return httpClient.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + token)
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(wsUrl), listener)
                    .orTimeout(10, TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            String sep = wsUrl.contains("?") ? "&" : "?";
            String url = wsUrl + sep + "token=" + urlEncode(token);
            return httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(url), listener)
                    .orTimeout(10, TimeUnit.SECONDS)
                    .join();
        }
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
            throw new RuntimeException("http status=" + resp.statusCode() + ", body=" + raw);
        }
        if (!extractBoolean(raw, "ok")) {
            throw new RuntimeException("http not ok: " + raw);
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
            throw new RuntimeException("http status=" + resp.statusCode() + ", body=" + raw);
        }
        return raw;
    }

    private static Args parseArgs(String[] argv) {
        Args a = new Args();
        for (int i = 0; i < argv.length; i++) {
            String k = argv[i];
            String v = (i + 1 < argv.length) ? argv[i + 1] : null;
            switch (k) {
                case "--wsList" -> { a.wsUrls = splitList(v); i++; }
                case "--http" -> { a.httpBase = v; i++; }
                case "--clients" -> { a.clients = Integer.parseInt(v); i++; }
                case "--senders" -> { a.senders = Integer.parseInt(v); i++; }
                case "--durationSeconds" -> { a.durationSeconds = Integer.parseInt(v); i++; }
                case "--warmupMs" -> { a.warmupMs = Integer.parseInt(v); i++; }
                case "--msgIntervalMs" -> { a.msgIntervalMs = Integer.parseInt(v); i++; }
                case "--userPrefix" -> { a.userPrefix = v; i++; }
                case "--password" -> { a.password = v; i++; }
                case "--receiverSamplePct" -> { a.receiverSamplePct = Integer.parseInt(v); i++; }
                case "--bodyBytes" -> { a.bodyBytes = Integer.parseInt(v); i++; }
                default -> { /* ignore */ }
            }
        }
        return a;
    }

    private static List<String> splitList(String s) {
        if (s == null || s.isBlank()) return List.of();
        String[] parts = s.split(";");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String buildBody(long sendTs, long seq, int bodyBytes) {
        String payload = "{\"sendTs\":" + sendTs + ",\"seq\":" + seq + "}";
        if (bodyBytes <= 0) return payload;
        int need = Math.max(0, bodyBytes - payload.length());
        if (need <= 0) return payload;
        StringBuilder sb = new StringBuilder(payload.length() + need + 16);
        sb.append(payload);
        sb.append("|pad=");
        for (int i = 0; i < need; i++) sb.append('a');
        return sb.toString();
    }

    private static Map<String, Object> pctJson(List<Long> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (values.isEmpty()) {
            out.put("p50", null);
            out.put("p95", null);
            out.put("p99", null);
            return out;
        }
        out.put("p50", percentile(values, 50));
        out.put("p95", percentile(values, 95));
        out.put("p99", percentile(values, 99));
        return out;
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        double rank = (p / 100.0) * (sorted.size() - 1);
        int idx = (int) Math.round(rank);
        idx = Math.min(sorted.size() - 1, Math.max(0, idx));
        return sorted.get(idx);
    }

    private static String round(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static String stripSlash(String s) {
        if (s == null) return "";
        return s.replaceAll("/+$", "");
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String jsonString(String s) {
        return "\"" + escapeJson(s) + "\"";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String requireField(String raw, String field) {
        String v = extractString(raw, field);
        if (v == null || v.isBlank()) {
            throw new RuntimeException("missing field '" + field + "' in: " + raw);
        }
        return v;
    }

    private static boolean extractBoolean(String raw, String key) {
        String needle = "\"" + key + "\":";
        int idx = raw.indexOf(needle);
        if (idx < 0) return false;
        int j = idx + needle.length();
        while (j < raw.length() && Character.isWhitespace(raw.charAt(j))) j++;
        return raw.startsWith("true", j);
    }

    private static String extractString(String raw, String key) {
        String needle = "\"" + key + "\":";
        int idx = raw.indexOf(needle);
        if (idx < 0) return null;
        int j = idx + needle.length();
        while (j < raw.length() && Character.isWhitespace(raw.charAt(j))) j++;
        if (j >= raw.length()) return null;
        char c = raw.charAt(j);
        if (c == '"') {
            int end = raw.indexOf('"', j + 1);
            if (end < 0) return null;
            return raw.substring(j + 1, end);
        }
        int end = j;
        while (end < raw.length() && raw.charAt(end) != ',' && raw.charAt(end) != '}' && !Character.isWhitespace(raw.charAt(end))) end++;
        return raw.substring(j, end);
    }

    private static Map<String, Object> parseJsonShallow(String s) {
        if (s == null) return Map.of();
        String t = s.trim();
        if (!t.startsWith("{") || !t.endsWith("}")) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        int i = 1;
        while (i < t.length() - 1) {
            i = skipWs(t, i);
            if (i >= t.length() - 1) break;
            if (t.charAt(i) == ',') { i++; continue; }
            if (t.charAt(i) != '"') break;
            int kEnd = t.indexOf('"', i + 1);
            if (kEnd < 0) break;
            String key = t.substring(i + 1, kEnd);
            i = skipWs(t, kEnd + 1);
            if (i >= t.length() || t.charAt(i) != ':') break;
            i = skipWs(t, i + 1);
            Object val;
            if (i < t.length() && t.charAt(i) == '"') {
                int vEnd = i + 1;
                StringBuilder sb = new StringBuilder();
                while (vEnd < t.length()) {
                    char c = t.charAt(vEnd);
                    if (c == '\\' && vEnd + 1 < t.length()) {
                        char n = t.charAt(vEnd + 1);
                        if (n == '"' || n == '\\' || n == '/') { sb.append(n); vEnd += 2; continue; }
                        if (n == 'n') { sb.append('\n'); vEnd += 2; continue; }
                        if (n == 'r') { sb.append('\r'); vEnd += 2; continue; }
                        if (n == 't') { sb.append('\t'); vEnd += 2; continue; }
                    }
                    if (c == '"') break;
                    sb.append(c);
                    vEnd++;
                }
                val = sb.toString();
                i = vEnd + 1;
            } else {
                int vEnd = i;
                while (vEnd < t.length() && t.charAt(vEnd) != ',' && t.charAt(vEnd) != '}') vEnd++;
                String raw = t.substring(i, vEnd).trim();
                if ("null".equals(raw)) val = null;
                else if ("true".equals(raw) || "false".equals(raw)) val = Boolean.parseBoolean(raw);
                else if (raw.matches("^-?\\d+$")) val = Long.parseLong(raw);
                else val = raw;
                i = vEnd;
            }
            out.put(key, val);
        }
        return out;
    }

    private static int skipWs(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') return i;
            i++;
        }
        return i;
    }

    private static String asString(Object v) {
        return (v == null) ? null : String.valueOf(v);
    }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }

    private static String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof Map<?, ?> m) {
            StringJoiner sj = new StringJoiner(",", "{", "}");
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sj.add(jsonString(String.valueOf(e.getKey())) + ":" + toJson(e.getValue()));
            }
            return sj.toString();
        }
        if (obj instanceof List<?> list) {
            StringJoiner sj = new StringJoiner(",", "[", "]");
            for (Object it : list) sj.add(toJson(it));
            return sj.toString();
        }
        if (obj instanceof String s) return jsonString(s);
        if (obj instanceof Boolean b) return b ? "true" : "false";
        if (obj instanceof Number n) return String.valueOf(n);
        return jsonString(String.valueOf(obj));
    }
}
