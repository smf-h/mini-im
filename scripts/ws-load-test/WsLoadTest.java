import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class WsLoadTest {

    private enum Mode { CONNECT, PING, SINGLE_E2E, ACK_STRESS }

    private static final class Args {
        Mode mode = Mode.PING;
        List<String> wsUrls = List.of("ws://127.0.0.1:9001/ws");
        boolean rolePinned = false;
        String jwtSecret = "change-me-please-change-me-please-change-me";
        String jwtIssuer = "mini-im";
        long sessionVersion = 0;
        int jwtTtlSeconds = 24 * 3600;
        long userBase = 100_000;

        int clients = 200;
        int durationSeconds = 60;
        int warmupMs = 1000;

        int pingIntervalMs = 1000;
        int msgIntervalMs = 100;
        boolean reconnect = true;
        int reconnectJitterMs = 200;

        int flapIntervalMs = 0;
        int flapPct = 0;
        int slowConsumerPct = 0;
        int slowConsumerDelayMs = 0;
        int noReadPct = 0;
        int bodyBytes = 0;
        int inflight = 0;

        boolean openLoop = false;
        int maxInflightHard = 200;
        long maxValidE2eMs = 10 * 60_000L;

        String ackStressTypes = "delivered,read";
        int ackEveryN = 1;
        boolean sendAckReceive = false;
        int drainMs = 1500;
    }

    private static final class Metrics {
        final AtomicLong connectAttempts = new AtomicLong();
        final AtomicLong connectOk = new AtomicLong();
        final AtomicLong connectFail = new AtomicLong();
        final AtomicLong authOk = new AtomicLong();
        final AtomicLong authFail = new AtomicLong();

        final AtomicLong pingSent = new AtomicLong();
        final AtomicLong pongRecv = new AtomicLong();
        final ConcurrentLinkedQueue<Long> pingRttMs = new ConcurrentLinkedQueue<>();

        final AtomicLong msgSent = new AtomicLong();
        final AtomicLong ackAccepted = new AtomicLong();
        final AtomicLong ackSaved = new AtomicLong();
        final AtomicLong msgRecv = new AtomicLong();
        final AtomicLong msgRecvUnique = new AtomicLong();
        final AtomicLong msgDup = new AtomicLong();
        final AtomicLong msgReorder = new AtomicLong();
        final AtomicLong msgReorderServerMsgId = new AtomicLong();
        final ConcurrentLinkedQueue<Long> acceptedLatencyMs = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Long> savedLatencyMs = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Long> e2eLatencyMs = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Long> e2eLatencyMsFast = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Long> e2eLatencyMsSlow = new ConcurrentLinkedQueue<>();
        final AtomicLong e2eInvalid = new AtomicLong();

        final AtomicLong wsError = new AtomicLong();
        final AtomicLong wsErrorRecvError = new AtomicLong();
        final AtomicLong wsErrorOnError = new AtomicLong();
        final AtomicLong wsErrorSendFail = new AtomicLong();
        final ConcurrentHashMap<String, AtomicLong> wsErrorByReason = new ConcurrentHashMap<>();

        final AtomicLong msgAttempted = new AtomicLong();
        final AtomicLong msgSkippedHard = new AtomicLong();

        final AtomicLong ackStressSent = new AtomicLong();
        final AtomicLong ackStressSentDelivered = new AtomicLong();
        final AtomicLong ackStressSentRead = new AtomicLong();
    }

    private static final class ClientCtx implements WebSocket.Listener {
        final Args args;
        final Metrics metrics;
        final HttpClient httpClient;
        final long userId;
        final boolean sender;
        final long peerUserId;

        final AtomicBoolean authed = new AtomicBoolean(false);
        final AtomicBoolean closed = new AtomicBoolean(false);
        final AtomicLong lastPingSentAt = new AtomicLong(0);
        final AtomicLong sendSeq = new AtomicLong(0);
        final Set<String> seenClientMsgId = ConcurrentHashMap.newKeySet();
        final Map<Long, Long> lastSeqByFrom = new ConcurrentHashMap<>();
        final AtomicLong lastServerMsgId = new AtomicLong(0);
        final boolean slowConsumer;
        final boolean noRead;
        final AtomicLong reconnectDelayMs;
        final AtomicInteger inflight = new AtomicInteger(0);
        final AtomicLong recvCount = new AtomicLong(0);
        final Map<String, Long> pendingSendTsByClientMsgId = new ConcurrentHashMap<>();

        volatile WebSocket ws;
        volatile CompletableFuture<WebSocket> wsFuture;

        ClientCtx(Args args, Metrics metrics, HttpClient httpClient, long userId, boolean sender, long peerUserId, boolean slowConsumer, boolean noRead) {
            this.args = args;
            this.metrics = metrics;
            this.httpClient = httpClient;
            this.userId = userId;
            this.sender = sender;
            this.peerUserId = peerUserId;
            this.slowConsumer = slowConsumer;
            this.noRead = noRead;
            this.reconnectDelayMs = new AtomicLong(100);
        }

        void connect() {
            metrics.connectAttempts.incrementAndGet();
            inflight.set(0);
            String token = issueAccessToken(args.jwtSecret, args.jwtIssuer, userId, args.sessionVersion, args.jwtTtlSeconds);
            String wsUrl = pickWsUrl(args.wsUrls, args.rolePinned, userId);
            String url = wsUrl + "?token=" + urlEncode(token);

            CompletableFuture<WebSocket> f = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(url), this);
            wsFuture = f;
            f.whenComplete((socket, ex) -> {
                if (ex != null) {
                    metrics.connectFail.incrementAndGet();
                    if (!closed.get() && args.reconnect) {
                        scheduleReconnect(this);
                    }
                } else {
                    ws = socket;
                    metrics.connectOk.incrementAndGet();
                    socket.sendText("{\"type\":\"AUTH\",\"token\":\"" + token + "\"}", true);
                }
            });
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
            String s = data.toString();
            Map<String, Object> msg = parseJsonShallow(s);
            String type = asString(msg.get("type"));
            if ("AUTH_OK".equals(type)) {
                authed.set(true);
                metrics.authOk.incrementAndGet();
                reconnectDelayMs.set(100);
            } else if ("AUTH_FAIL".equals(type)) {
                metrics.authFail.incrementAndGet();
            } else if ("ERROR".equals(type)) {
                metrics.wsError.incrementAndGet();
                metrics.wsErrorRecvError.incrementAndGet();
                String reason = asString(msg.get("reason"));
                if (reason == null || reason.isBlank()) {
                    reason = "unknown";
                }
                metrics.wsErrorByReason.computeIfAbsent(reason, k -> new AtomicLong()).incrementAndGet();
            } else if ("PONG".equals(type)) {
                long sentAt = lastPingSentAt.getAndSet(0);
                if (sentAt > 0) {
                    metrics.pongRecv.incrementAndGet();
                    metrics.pingRttMs.add(System.currentTimeMillis() - sentAt);
                }
            } else if ("ACK".equals(type)) {
                String ackType = asString(msg.get("ackType"));
                Long ackFrom = asLong(msg.get("from"));
                String clientMsgId = asString(msg.get("clientMsgId"));

                if ("accepted".equals(ackType)) {
                    metrics.ackAccepted.incrementAndGet();
                    if (ackFrom != null && clientMsgId != null && !clientMsgId.isBlank()) {
                        ClientCtx senderCtx = clientsByUserId.get(ackFrom);
                        if (senderCtx != null && senderCtx.sender) {
                            Long sendTs = senderCtx.pendingSendTsByClientMsgId.get(clientMsgId);
                            if (sendTs != null && sendTs > 0) {
                                long lat = System.currentTimeMillis() - sendTs;
                                if (lat >= 0 && lat <= args.maxValidE2eMs) {
                                    metrics.acceptedLatencyMs.add(lat);
                                }
                            }
                        }
                    }
                } else if ("saved".equals(ackType)) {
                    metrics.ackSaved.incrementAndGet();
                    if (ackFrom != null && clientMsgId != null && !clientMsgId.isBlank()) {
                        ClientCtx senderCtx = clientsByUserId.get(ackFrom);
                        if (senderCtx != null && senderCtx.sender) {
                            Long sendTs = senderCtx.pendingSendTsByClientMsgId.remove(clientMsgId);
                            if (sendTs != null && sendTs > 0) {
                                long lat = System.currentTimeMillis() - sendTs;
                                if (lat >= 0 && lat <= args.maxValidE2eMs) {
                                    metrics.savedLatencyMs.add(lat);
                                }
                            }
                            senderCtx.inflight.updateAndGet(vv -> Math.max(0, vv - 1));
                        }
                    }
                }
            } else if ("SINGLE_CHAT".equals(type)) {
                metrics.msgRecv.incrementAndGet();
                String clientMsgId = asString(msg.get("clientMsgId"));
                if (clientMsgId != null && !clientMsgId.isBlank()) {
                    if (!seenClientMsgId.add(clientMsgId)) {
                        metrics.msgDup.incrementAndGet();
                    } else {
                        metrics.msgRecvUnique.incrementAndGet();
                    }
                }

                Long smid = asLong(msg.get("serverMsgId"));
                if (smid != null) {
                    long prev = lastServerMsgId.get();
                    if (smid <= prev) {
                        metrics.msgReorderServerMsgId.incrementAndGet();
                    } else {
                        lastServerMsgId.set(smid);
                    }
                }

                // ACK_RECEIVED: 避免 cron resend / 兜底补发导致重复投递与乱序
                Long from = asLong(msg.get("from"));
                if (args.sendAckReceive && !sender && from != null && smid != null && clientMsgId != null && !clientMsgId.isBlank()) {
                    long now = System.currentTimeMillis();
                    String ack = new StringJoiner(",", "{", "}")
                            .add("\"type\":\"ACK\"")
                            .add("\"clientMsgId\":\"" + escapeJson(clientMsgId) + "\"")
                            .add("\"serverMsgId\":" + smid)
                            .add("\"to\":" + from)
                            .add("\"ackType\":\"ack_receive\"")
                            .add("\"ts\":" + now)
                            .toString();
                    WebSocket socket = this.ws;
                    if (socket != null) socket.sendText(ack, true);
                }

                if (args.mode == Mode.ACK_STRESS && !sender && smid != null) {
                    long n = recvCount.incrementAndGet();
                    int every = Math.max(1, args.ackEveryN);
                    if (n % every == 0) {
                        WebSocket socket = this.ws;
                        if (socket != null) {
                            long now = System.currentTimeMillis();
                            if (ackStressHas(args, "delivered")) {
                                String ack = new StringJoiner(",", "{", "}")
                                        .add("\"type\":\"ACK\"")
                                        .add("\"clientMsgId\":\"ack-delivered-" + userId + "-" + smid + "\"")
                                        .add("\"serverMsgId\":" + smid)
                                        .add("\"ackType\":\"delivered\"")
                                        .add("\"ts\":" + now)
                                        .toString();
                                metrics.ackStressSent.incrementAndGet();
                                metrics.ackStressSentDelivered.incrementAndGet();
                                socket.sendText(ack, true).whenComplete((ig, err) -> {
                                    if (err != null) metrics.wsError.incrementAndGet();
                                });
                            }
                            if (ackStressHas(args, "read")) {
                                String ack = new StringJoiner(",", "{", "}")
                                        .add("\"type\":\"ACK\"")
                                        .add("\"clientMsgId\":\"ack-read-" + userId + "-" + smid + "\"")
                                        .add("\"serverMsgId\":" + smid)
                                        .add("\"ackType\":\"read\"")
                                        .add("\"ts\":" + now)
                                        .toString();
                                metrics.ackStressSent.incrementAndGet();
                                metrics.ackStressSentRead.incrementAndGet();
                                socket.sendText(ack, true).whenComplete((ig, err) -> {
                                    if (err != null) metrics.wsError.incrementAndGet();
                                });
                            }
                        }
                    }
                }
                String body = asString(msg.get("body"));
                if (body != null) {
                    Map<String, Object> payload = parseJsonShallow(body);
                    Long sendTs = asLong(payload.get("sendTs"));
                    if (sendTs != null) {
                        long e2e = System.currentTimeMillis() - sendTs;
                        if (e2e >= 0 && e2e <= args.maxValidE2eMs) {
                            metrics.e2eLatencyMs.add(e2e);
                            if (slowConsumer) {
                                metrics.e2eLatencyMsSlow.add(e2e);
                            } else {
                                metrics.e2eLatencyMsFast.add(e2e);
                            }
                        } else {
                            metrics.e2eInvalid.incrementAndGet();
                        }
                    }
                    Long seq = asLong(payload.get("seq"));
                    if (seq != null && from != null) {
                        long lastSeq = lastSeqByFrom.getOrDefault(from, 0L);
                        if (seq <= lastSeq) {
                            metrics.msgReorder.incrementAndGet();
                        } else {
                            lastSeqByFrom.put(from, seq);
                        }
                    }
                }
            }

            // Network-level slow consumer simulation: after auth, stop requesting new messages.
            // This tries to hold back WebSocket reads (backpressure) rather than only delaying application processing.
            if (noRead && authed.get() && !"AUTH_OK".equals(type)) {
                return null;
            }
            if (slowConsumer && authed.get() && args.slowConsumerDelayMs > 0 && !"AUTH_OK".equals(type)) {
                scheduler.schedule(() -> webSocket.request(1), args.slowConsumerDelayMs, TimeUnit.MILLISECONDS);
            } else {
                webSocket.request(1);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            authed.set(false);
            if (!closed.get() && args.reconnect) {
                scheduleReconnect(this);
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            authed.set(false);
            if (!stopping.get() && !closed.get()) {
                metrics.wsError.incrementAndGet();
                metrics.wsErrorOnError.incrementAndGet();
            }
            if (!stopping.get() && !closed.get() && args.reconnect) {
                scheduleReconnect(this);
            }
        }
    }

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors()),
            new ThreadFactory() {
                final AtomicInteger idx = new AtomicInteger();
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "ws-load-" + idx.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            }
    );
    private static final Random rnd = new Random();
    private static final Map<Long, ClientCtx> clientsByUserId = new ConcurrentHashMap<>();
    private static final AtomicBoolean stopping = new AtomicBoolean(false);

    public static void main(String[] argv) throws Exception {
        Args args = parseArgs(argv);
        Metrics m = new Metrics();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newFixedThreadPool(Math.min(64, Math.max(8, args.clients / 50))))
                .build();

        if (args.mode == Mode.SINGLE_E2E && args.clients % 2 != 0) {
            throw new IllegalArgumentException("--clients must be even for SINGLE_E2E");
        }
        if (args.mode == Mode.ACK_STRESS && args.clients % 2 != 0) {
            throw new IllegalArgumentException("--clients must be even for ACK_STRESS");
        }
        if (args.rolePinned && args.wsUrls.size() < 2) {
            throw new IllegalArgumentException("--rolePinned requires at least 2 ws urls");
        }

        List<ClientCtx> clients = new ArrayList<>(args.clients);
        clientsByUserId.clear();
        for (int i = 0; i < args.clients; i++) {
            long uid = args.userBase + i;
            boolean sender = (args.mode == Mode.SINGLE_E2E || args.mode == Mode.ACK_STRESS) && (i % 2 == 0);
            long peer = sender ? uid + 1 : uid - 1;
            boolean slow = false;
            boolean noRead = false;
            if (args.slowConsumerPct > 0 && !sender) {
                slow = rnd.nextInt(100) < args.slowConsumerPct;
            }
            if (args.noReadPct > 0 && !sender) {
                noRead = rnd.nextInt(100) < args.noReadPct;
                if (noRead) {
                    // noRead implies "do not request more"; keep slowConsumer as false to avoid mixing two mechanisms.
                    slow = false;
                }
            }
            ClientCtx c = new ClientCtx(args, m, httpClient, uid, sender, peer, slow, noRead);
            clients.add(c);
            clientsByUserId.put(uid, c);
        }

        long startMs = System.currentTimeMillis();
        for (ClientCtx c : clients) c.connect();

        Thread.sleep(args.warmupMs);

        if (args.mode == Mode.PING) {
            scheduler.scheduleAtFixedRate(() -> {
                for (ClientCtx c : clients) {
                    if (!c.authed.get()) continue;
                    long t = System.currentTimeMillis();
                    c.lastPingSentAt.set(t);
                    WebSocket ws = c.ws;
                    if (ws != null) {
                        m.pingSent.incrementAndGet();
                        ws.sendText("{\"type\":\"PING\"}", true);
                    }
                }
            }, 0, args.pingIntervalMs, TimeUnit.MILLISECONDS);
        }

        if (args.flapIntervalMs > 0 && args.flapPct > 0) {
            scheduler.scheduleAtFixedRate(() -> {
                for (ClientCtx c : clients) {
                    if (rnd.nextInt(100) >= args.flapPct) continue;
                    WebSocket ws = c.ws;
                    if (ws != null) {
                        try {
                            ws.sendClose(WebSocket.NORMAL_CLOSURE, "flap");
                        } catch (Exception ignored) {
                        }
                    }
                }
            }, args.flapIntervalMs, args.flapIntervalMs, TimeUnit.MILLISECONDS);
        }

        if (args.mode == Mode.SINGLE_E2E || args.mode == Mode.ACK_STRESS) {
            // Avoid micro-bursts: the old implementation sent from all senders in the same scheduler tick,
            // which creates a 2500-msg burst every msgIntervalMs (e.g. every 3s) and inflates wsError/queueing.
            // Spread each sender with a deterministic per-user offset in [0, msgIntervalMs).
            int interval = Math.max(1, args.msgIntervalMs);
            for (ClientCtx c : clients) {
                if (!c.sender) continue;
                long mixed = mix64(c.userId);
                long initDelay = Math.floorMod(Long.hashCode(mixed), interval);
                scheduler.scheduleAtFixedRate(() -> trySendSingleChat(c, args, m), initDelay, interval, TimeUnit.MILLISECONDS);
            }
        }

        long endMs = startMs + args.durationSeconds * 1000L;
        while (System.currentTimeMillis() < endMs) {
            Thread.sleep(1000);
        }

        // Stop periodic senders; give receivers a short drain window before closing sockets.
        stopping.set(true);
        if (args.drainMs > 0) {
            Thread.sleep(args.drainMs);
        }

        for (ClientCtx c : clients) c.close();
        scheduler.shutdownNow();
        httpClient.executor().ifPresent(e -> ((java.util.concurrent.ExecutorService) e).shutdownNow());

        printSummary(args, m, startMs, endMs);
    }

    private static void scheduleReconnect(ClientCtx c) {
        long base = c.reconnectDelayMs.get();
        long next = Math.min(5000, Math.max(100, base * 2));
        c.reconnectDelayMs.set(next);
        long jitter = (c.args.reconnectJitterMs <= 0) ? 0 : rnd.nextInt(c.args.reconnectJitterMs);
        long delay = Math.min(5000, next + jitter);
        scheduler.schedule(() -> {
            if (!c.closed.get()) c.connect();
        }, delay, TimeUnit.MILLISECONDS);
    }

    private static String pickWsUrl(List<String> urls, boolean pinned, long userId) {
        if (urls.isEmpty()) return "ws://127.0.0.1:9001/ws";
        if (!pinned || urls.size() == 1) return urls.get(rnd.nextInt(urls.size()));
        // When clients are created with sequential userIds and senders are every other user (step=2),
        // a naive `userId % N` pinning causes severe skew when N is even (only half buckets used).
        // Use a cheap stable mix to avoid gcd/stride artifacts while keeping deterministic pinning.
        long mixed = mix64(userId);
        int idx = Math.floorMod(Long.hashCode(mixed), urls.size());
        return urls.get(idx);
    }

    private static void trySendSingleChat(ClientCtx c, Args args, Metrics m) {
        if (stopping.get()) return;
        if (!c.sender) return;
        if (!c.authed.get()) return;
        WebSocket ws = c.ws;
        if (ws == null) return;

        m.msgAttempted.incrementAndGet();
        if (args.openLoop) {
            int hard = Math.max(1, args.maxInflightHard);
            if (c.inflight.get() >= hard) {
                m.msgSkippedHard.incrementAndGet();
                return;
            }
        } else {
            int lim = (args.inflight <= 0) ? Integer.MAX_VALUE : Math.max(1, args.inflight);
            if (c.inflight.get() >= lim) return;
        }

        long seq = c.sendSeq.incrementAndGet();
        String clientMsgId = c.userId + "-" + seq;
        long sendTs = System.currentTimeMillis();
        String body = buildBody(sendTs, seq, args.bodyBytes);
        String msg = new StringJoiner(",", "{", "}")
                .add("\"type\":\"SINGLE_CHAT\"")
                .add("\"to\":" + c.peerUserId)
                .add("\"clientMsgId\":\"" + clientMsgId + "\"")
                .add("\"msgType\":\"TEXT\"")
                .add("\"body\":\"" + escapeJson(body) + "\"")
                .add("\"ts\":" + sendTs)
                .toString();
        m.msgSent.incrementAndGet();
        c.inflight.incrementAndGet();
        c.pendingSendTsByClientMsgId.put(clientMsgId, sendTs);
        ws.sendText(msg, true).whenComplete((ignored, err) -> {
            if (err != null) {
                c.inflight.updateAndGet(vv -> Math.max(0, vv - 1));
                c.pendingSendTsByClientMsgId.remove(clientMsgId);
                if (!stopping.get()) {
                    m.wsError.incrementAndGet();
                    m.wsErrorSendFail.incrementAndGet();
                }
            }
        });
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    private static String buildBody(long sendTs, long seq, int targetLen) {
        String base = "{\"sendTs\":" + sendTs + ",\"seq\":" + seq + "}";
        if (targetLen <= 0) {
            return base;
        }
        int clamped = Math.min(4096, Math.max(1, targetLen));
        if (base.length() >= clamped) {
            return base;
        }

        String prefix = "{\"sendTs\":" + sendTs + ",\"seq\":" + seq + ",\"pad\":\"";
        String suffix = "\"}";
        int padLen = clamped - prefix.length() - suffix.length();
        if (padLen <= 0) {
            return base;
        }
        return prefix + "a".repeat(padLen) + suffix;
    }

    private static void printSummary(Args args, Metrics m, long startMs, long endMs) {
        long durMs = Math.max(1, endMs - startMs);
        double durSec = durMs / 1000.0;

        List<Long> ping = new ArrayList<>(m.pingRttMs);
        List<Long> accepted = new ArrayList<>(m.acceptedLatencyMs);
        List<Long> saved = new ArrayList<>(m.savedLatencyMs);
        List<Long> e2e = new ArrayList<>(m.e2eLatencyMs);
        List<Long> e2eFast = new ArrayList<>(m.e2eLatencyMsFast);
        List<Long> e2eSlow = new ArrayList<>(m.e2eLatencyMsSlow);
        Collections.sort(ping);
        Collections.sort(accepted);
        Collections.sort(saved);
        Collections.sort(e2e);
        Collections.sort(e2eFast);
        Collections.sort(e2eSlow);

        System.out.println("{");
        System.out.println("  \"mode\": \"" + args.mode + "\",");
        System.out.println("  \"wsUrls\": \"" + String.join(";", args.wsUrls) + "\",");
        System.out.println("  \"clients\": " + args.clients + ",");
        System.out.println("  \"durationSeconds\": " + args.durationSeconds + ",");
        System.out.println("  \"userBase\": " + args.userBase + ",");
        System.out.println("  \"rolePinned\": " + args.rolePinned + ",");
        System.out.println("  \"msgIntervalMs\": " + args.msgIntervalMs + ",");
        System.out.println("  \"pingIntervalMs\": " + args.pingIntervalMs + ",");
        System.out.println("  \"reconnect\": " + args.reconnect + ",");
        System.out.println("  \"flapIntervalMs\": " + args.flapIntervalMs + ",");
        System.out.println("  \"flapPct\": " + args.flapPct + ",");
        System.out.println("  \"slowConsumerPct\": " + args.slowConsumerPct + ",");
        System.out.println("  \"slowConsumerDelayMs\": " + args.slowConsumerDelayMs + ",");
        System.out.println("  \"noReadPct\": " + args.noReadPct + ",");
        System.out.println("  \"bodyBytes\": " + args.bodyBytes + ",");
        System.out.println("  \"inflight\": " + args.inflight + ",");
        System.out.println("  \"openLoop\": " + args.openLoop + ",");
        System.out.println("  \"maxInflightHard\": " + args.maxInflightHard + ",");
        System.out.println("  \"maxValidE2eMs\": " + args.maxValidE2eMs + ",");
        System.out.println("  \"sendAckReceive\": " + args.sendAckReceive + ",");
        System.out.println("  \"drainMs\": " + args.drainMs + ",");
        System.out.println("  \"connect\": {\"attempts\": " + m.connectAttempts.get() + ", \"ok\": " + m.connectOk.get() + ", \"fail\": " + m.connectFail.get() + "},");
        System.out.println("  \"auth\": {\"ok\": " + m.authOk.get() + ", \"fail\": " + m.authFail.get() + "},");
        System.out.println("  \"errors\": {\"wsError\": " + m.wsError.get() + "},");
        System.out.println("  \"errorsDetail\": {\"recvError\": " + m.wsErrorRecvError.get() + ", \"onError\": " + m.wsErrorOnError.get() + ", \"sendFail\": " + m.wsErrorSendFail.get() + "},");
        System.out.println("  \"errorsByReason\": " + mapJson(m.wsErrorByReason) + ",");
        if (args.mode == Mode.PING) {
            System.out.println("  \"ping\": {\"sent\": " + m.pingSent.get() + ", \"pong\": " + m.pongRecv.get() + ", \"rttMs\": " + pctJson(ping) + "},");
        }
        if (args.mode == Mode.SINGLE_E2E) {
            System.out.println("  \"singleChat\": {\"attempted\": " + m.msgAttempted.get() + ", \"attemptedPerSec\": " + round(m.msgAttempted.get() / durSec)
                    + ", \"sent\": " + m.msgSent.get() + ", \"sentPerSec\": " + round(m.msgSent.get() / durSec)
                    + ", \"skippedHard\": " + m.msgSkippedHard.get()
                    + ", \"ackAccepted\": " + m.ackAccepted.get()
                    + ", \"acceptedMs\": " + pctJson(accepted)
                    + ", \"ackSaved\": " + m.ackSaved.get()
                    + ", \"savedMs\": " + pctJson(saved)
                    + ", \"recv\": " + m.msgRecv.get() + ", \"recvUnique\": " + m.msgRecvUnique.get() + ", \"dup\": " + m.msgDup.get() + ", \"reorder\": " + m.msgReorder.get()
                    + ", \"reorderByFrom\": " + m.msgReorder.get() + ", \"reorderByServerMsgId\": " + m.msgReorderServerMsgId.get()
                    + ", \"e2eMs\": " + pctJson(e2e)
                    + ", \"e2eInvalid\": " + m.e2eInvalid.get()
                    + ", \"e2eMsFast\": " + pctJson(e2eFast)
                    + ", \"e2eMsSlow\": " + pctJson(e2eSlow)
                    + "},");
        }
        if (args.mode == Mode.ACK_STRESS) {
            System.out.println("  \"ackStress\": {\"types\": \"" + escapeJson(args.ackStressTypes) + "\""
                    + ", \"ackEveryN\": " + args.ackEveryN
                    + ", \"attempted\": " + m.msgAttempted.get() + ", \"attemptedPerSec\": " + round(m.msgAttempted.get() / durSec)
                    + ", \"sent\": " + m.msgSent.get() + ", \"sentPerSec\": " + round(m.msgSent.get() / durSec)
                    + ", \"skippedHard\": " + m.msgSkippedHard.get()
                    + ", \"acksSent\": " + m.ackStressSent.get()
                    + ", \"acksSentDelivered\": " + m.ackStressSentDelivered.get()
                    + ", \"acksSentRead\": " + m.ackStressSentRead.get()
                    + ", \"recv\": " + m.msgRecv.get()
                    + "},");
        }
        System.out.println("  \"note\": \"Single-node results only; use for baseline/regression\""); 
        System.out.println("}");
    }

    private static String mapJson(ConcurrentHashMap<String, AtomicLong> m) {
        if (m == null || m.isEmpty()) return "{}";
        List<Map.Entry<String, AtomicLong>> entries = new ArrayList<>(m.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()));
        StringJoiner sj = new StringJoiner(",", "{", "}");
        for (Map.Entry<String, AtomicLong> e : entries) {
            String k = e.getKey();
            if (k == null) k = "null";
            sj.add("\"" + escapeJson(k) + "\":" + e.getValue().get());
        }
        return sj.toString();
    }

    private static String pctJson(List<Long> values) {
        if (values.isEmpty()) return "{\"p50\":null,\"p95\":null,\"p99\":null}";
        return "{\"p50\":" + percentile(values, 50) + ",\"p95\":" + percentile(values, 95) + ",\"p99\":" + percentile(values, 99) + "}";
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

    private static Args parseArgs(String[] argv) {
        Args a = new Args();
        for (int i = 0; i < argv.length; i++) {
            String k = argv[i];
            String v = (i + 1 < argv.length) ? argv[i + 1] : null;
            switch (k) {
                case "--mode" -> { a.mode = Mode.valueOf(v.toUpperCase()); i++; }
                case "--ws" -> { a.wsUrls = List.of(v); i++; }
                case "--wsList" -> { a.wsUrls = splitList(v); i++; }
                case "--rolePinned" -> { a.rolePinned = true; }
                case "--jwtSecret" -> { a.jwtSecret = v; i++; }
                case "--jwtIssuer" -> { a.jwtIssuer = v; i++; }
                case "--sessionVersion" -> { a.sessionVersion = Long.parseLong(v); i++; }
                case "--jwtTtlSeconds" -> { a.jwtTtlSeconds = Integer.parseInt(v); i++; }
                case "--userBase" -> { a.userBase = Long.parseLong(v); i++; }
                case "--clients" -> { a.clients = Integer.parseInt(v); i++; }
                case "--durationSeconds" -> { a.durationSeconds = Integer.parseInt(v); i++; }
                case "--warmupMs" -> { a.warmupMs = Integer.parseInt(v); i++; }
                case "--pingIntervalMs" -> { a.pingIntervalMs = Integer.parseInt(v); i++; }
                case "--msgIntervalMs" -> { a.msgIntervalMs = Integer.parseInt(v); i++; }
                case "--reconnect" -> { a.reconnect = Boolean.parseBoolean(v); i++; }
                case "--reconnectJitterMs" -> { a.reconnectJitterMs = Integer.parseInt(v); i++; }
                case "--flapIntervalMs" -> { a.flapIntervalMs = Integer.parseInt(v); i++; }
                case "--flapPct" -> { a.flapPct = Integer.parseInt(v); i++; }
                case "--slowConsumerPct" -> { a.slowConsumerPct = Integer.parseInt(v); i++; }
                case "--slowConsumerDelayMs" -> { a.slowConsumerDelayMs = Integer.parseInt(v); i++; }
                case "--noReadPct" -> { a.noReadPct = Integer.parseInt(v); i++; }
                case "--bodyBytes" -> { a.bodyBytes = Integer.parseInt(v); i++; }
                case "--inflight" -> { a.inflight = Integer.parseInt(v); i++; }
                case "--openLoop" -> { a.openLoop = true; }
                case "--maxInflightHard" -> { a.maxInflightHard = Integer.parseInt(v); i++; }
                case "--maxValidE2eMs" -> { a.maxValidE2eMs = Long.parseLong(v); i++; }
                case "--ackStressTypes" -> { a.ackStressTypes = v; i++; }
                case "--ackEveryN" -> { a.ackEveryN = Integer.parseInt(v); i++; }
                case "--sendAckReceive" -> { a.sendAckReceive = true; }
                case "--drainMs" -> { a.drainMs = Integer.parseInt(v); i++; }
                default -> { /* ignore */ }
            }
        }
        return a;
    }

    private static boolean ackStressHas(Args args, String t) {
        if (args.ackStressTypes == null || args.ackStressTypes.isBlank()) return false;
        String raw = args.ackStressTypes.toLowerCase(java.util.Locale.ROOT);
        String[] parts = raw.split("[,;\\s]+");
        for (String p : parts) {
            if (p.isBlank()) continue;
            if (p.equals(t)) return true;
        }
        return false;
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

    private static String issueAccessToken(String secret, String issuer, long uid, long sv, int ttlSeconds) {
        long nowSec = Instant.now().getEpochSecond();
        long expSec = nowSec + ttlSeconds;

        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payload = "{\"uid\":" + uid + ",\"typ\":\"access\",\"sv\":" + sv + ",\"iss\":\"" + escapeJson(issuer) + "\",\"iat\":" + nowSec + ",\"exp\":" + expSec + "}";

        String h = b64url(header.getBytes(StandardCharsets.UTF_8));
        String p = b64url(payload.getBytes(StandardCharsets.UTF_8));
        String data = h + "." + p;
        String sig = hmacSha256B64Url(secret.getBytes(StandardCharsets.UTF_8), data.getBytes(StandardCharsets.UTF_8));
        return data + "." + sig;
    }

    private static String b64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hmacSha256B64Url(byte[] secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return b64url(mac.doFinal(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Map<String, Object> parseJsonShallow(String s) {
        if (s == null) return Map.of();
        String t = s.trim();
        if (!t.startsWith("{") || !t.endsWith("}")) return Map.of();
        Map<String, Object> out = new java.util.HashMap<>();
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

    private static String asString(Object o) {
        if (o == null) return null;
        return String.valueOf(o);
    }

    private static Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception ignored) { return null; }
    }
}
