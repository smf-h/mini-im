import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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
 * -Dscenario=basic|idempotency|offline|cron|auth|all
 * -DauthTokenTtlSeconds=2 (auth only)
 * -DredisHost=127.0.0.1 -DredisPort=6379 -DredisPassword= (optional; for auth scenario)
 */
public class WsSmokeTest {

    private static final String WS_URL = System.getProperty("ws", "ws://127.0.0.1:9001/ws");
    private static final String JWT_SECRET = System.getProperty("jwtSecret", "change-me-please-change-me-please-change-me");

    private static final long USER_A = Long.parseLong(System.getProperty("userA", "10001"));
    private static final long USER_B = Long.parseLong(System.getProperty("userB", "10002"));

    private static final long TIMEOUT_MS = Long.parseLong(System.getProperty("timeoutMs", "8000"));
    private static final String SCENARIO = System.getProperty("scenario", "all").trim().toLowerCase();
    private static final long AUTH_TOKEN_TTL_SECONDS = Long.parseLong(System.getProperty("authTokenTtlSeconds", "2"));

    private static final String REDIS_HOST = System.getProperty("redisHost", "127.0.0.1");
    private static final int REDIS_PORT = Integer.parseInt(System.getProperty("redisPort", "6379"));
    private static final String REDIS_PASSWORD = System.getProperty("redisPassword", "");

    public static void main(String[] args) throws Exception {
        String tokenA = issueAccessToken(USER_A);
        String tokenB = issueAccessToken(USER_B);

        // 为了输出更可读且稳定，使用 LinkedHashMap 固定字段顺序
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("ws", WS_URL);
        vars.put("scenario", SCENARIO);
        vars.put("userA", USER_A);
        vars.put("userB", USER_B);
        vars.put("timeoutMs", TIMEOUT_MS);
        vars.put("authTokenTtlSeconds", AUTH_TOKEN_TTL_SECONDS);
        vars.put("redisHost", REDIS_HOST);
        vars.put("redisPort", REDIS_PORT);
        out.put("vars", vars);
        Map<String, Object> explain = new LinkedHashMap<>();
        explain.put("auth.handshake", "WS handshake validates accessToken (Authorization/query); failures are 401 missing_access_token/invalid_access_token");
        explain.put("auth.frame", "WS AUTH/REAUTH frame binds session or refreshes expMs; AUTH_OK means token parsed and session bound/refreshed");
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
            if ("cron".equals(SCENARIO) || "all".equals(SCENARIO)) {
                scenarios.put("cron_resend_no_ack_receive", scenarioCronResend(tokenA, tokenB));
            }
            if ("auth".equals(SCENARIO) || "all".equals(SCENARIO)) {
                scenarios.put("auth_chain", scenarioAuthChain());
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
        List<Map<String, Object>> steps = new ArrayList<>();
        r.put("steps", steps);

        HttpClient httpClient = HttpClient.newHttpClient();
        QueueListener listenerA = new QueueListener();
        QueueListener listenerB = new QueueListener();

        WebSocket wsA = null;
        WebSocket wsB = null;
        try {
            wsA = connect(httpClient, tokenA, listenerA);
            wsB = connect(httpClient, tokenB, listenerB);
            steps.add(step("A->S", "AUTH", authJsonMasked(tokenA)));
            auth(wsA, tokenA, listenerA.queue);
            steps.add(step("B->S", "AUTH", authJsonMasked(tokenB)));
            auth(wsB, tokenB, listenerB.queue);

            String clientMsgId = "basic-" + UUID.randomUUID();
            String sendJson = singleChatJson(clientMsgId, USER_B, "hello-basic");
            steps.add(step("A->S", "SINGLE_CHAT send", sendJson));
            sendText(wsA, sendJson);

            String ackSaved = await(listenerA.queue, TIMEOUT_MS, isAckSaved(clientMsgId));
            steps.add(step("S->A", "ACK(saved)", ackSaved));
            String serverMsgId = requireField(ackSaved, "serverMsgId");

            String delivered = await(listenerB.queue, TIMEOUT_MS, isSingleChat(clientMsgId, serverMsgId));
            steps.add(step("S->B", "SINGLE_CHAT deliver", delivered));

            String ackReceiveJson = ackReceiveJson(clientMsgId, serverMsgId, USER_A);
            steps.add(step("B->S", "ACK(ack_receive)", ackReceiveJson));
            sendText(wsB, ackReceiveJson);

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
        List<Map<String, Object>> steps = new ArrayList<>();
        r.put("steps", steps);

        HttpClient httpClient = HttpClient.newHttpClient();
        QueueListener listenerA = new QueueListener();
        QueueListener listenerB = new QueueListener();

        WebSocket wsA = null;
        WebSocket wsB = null;
        try {
            wsA = connect(httpClient, tokenA, listenerA);
            wsB = connect(httpClient, tokenB, listenerB);
            steps.add(step("A->S", "AUTH", authJsonMasked(tokenA)));
            auth(wsA, tokenA, listenerA.queue);
            steps.add(step("B->S", "AUTH", authJsonMasked(tokenB)));
            auth(wsB, tokenB, listenerB.queue);

            String clientMsgId = "idem-" + UUID.randomUUID();

            String send1 = singleChatJson(clientMsgId, USER_B, "hello-idem-1");
            steps.add(step("A->S", "SINGLE_CHAT send #1", send1));
            sendText(wsA, send1);
            String ack1 = await(listenerA.queue, TIMEOUT_MS, isAckSaved(clientMsgId));
            steps.add(step("S->A", "ACK(saved) #1", ack1));
            String serverMsgId1 = requireField(ack1, "serverMsgId");

            // 立即“重发同一个 clientMsgId”
            String send2 = singleChatJson(clientMsgId, USER_B, "hello-idem-2");
            steps.add(step("A->S", "SINGLE_CHAT resend #2", send2));
            sendText(wsA, send2);
            String ack2 = await(listenerA.queue, TIMEOUT_MS, isAckSaved(clientMsgId));
            steps.add(step("S->A", "ACK(saved) #2", ack2));
            String serverMsgId2 = requireField(ack2, "serverMsgId");

            boolean same = serverMsgId1.equals(serverMsgId2);
            if (!same) {
                throw new RuntimeException("idempotency violated: serverMsgId changed");
            }

            // 清理：让 B 把其中一条消息 ACK_RECEIVED 掉，避免 DB 一直积压（不严格要求）
            String delivered = await(listenerB.queue, TIMEOUT_MS, isSingleChat(clientMsgId, serverMsgId1));
            steps.add(step("S->B", "SINGLE_CHAT deliver", delivered));

            String ackReceiveJson = ackReceiveJson(clientMsgId, serverMsgId1, USER_A);
            steps.add(step("B->S", "ACK(ack_receive)", ackReceiveJson));
            sendText(wsB, ackReceiveJson);
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
        List<Map<String, Object>> steps = new ArrayList<>();
        r.put("steps", steps);

        HttpClient httpClient = HttpClient.newHttpClient();
        QueueListener listenerA = new QueueListener();

        WebSocket wsA = null;
        try {
            wsA = connect(httpClient, tokenA, listenerA);
            steps.add(step("A->S", "AUTH", authJsonMasked(tokenA)));
            auth(wsA, tokenA, listenerA.queue);

            String clientMsgId = "offline-" + UUID.randomUUID();
            String sendJson = singleChatJson(clientMsgId, USER_B, "hello-offline");
            steps.add(step("A->S", "SINGLE_CHAT send (B offline)", sendJson));
            sendText(wsA, sendJson);

            String ackSaved = await(listenerA.queue, TIMEOUT_MS, isAckSaved(clientMsgId));
            steps.add(step("S->A", "ACK(saved)", ackSaved));
            String serverMsgId = requireField(ackSaved, "serverMsgId");

            // 此时 B 仍未上线，按设计 DB 应为 DROPPED；我们不在 Java 里访问 DB，
            // 由 PowerShell 脚本用 mysql CLI 做校验并附加到输出中。

            // B 上线：AUTH 后应触发“DROPPED 拉取并推送”
            QueueListener listenerB = new QueueListener();
            WebSocket wsB = null;
            try {
                wsB = connect(httpClient, tokenB, listenerB);
                steps.add(step("B->S", "AUTH (B online)", authJsonMasked(tokenB)));
                auth(wsB, tokenB, listenerB.queue);

                // 期待：收到之前那条离线消息
                String delivered = await(listenerB.queue, TIMEOUT_MS, isSingleChat(clientMsgId, serverMsgId));
                steps.add(step("S->B", "SINGLE_CHAT resend (from DROPPED)", delivered));

                // 收件人确认收到：回 ACK_RECEIVED（ack_receive）
                String ackReceiveJson = ackReceiveJson(clientMsgId, serverMsgId, USER_A);
                steps.add(step("B->S", "ACK(ack_receive)", ackReceiveJson));
                sendText(wsB, ackReceiveJson);
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

    /**
     * 场景 4：定时任务补发（WsCron）
     * 目标：
     * - A/B 都在线
     * - B 收到消息后“故意不回 ACK_RECEIVED”，让消息状态停留在 SAVED
     * - 等待 WsCron 扫描到该条 SAVED 消息后再次下发（补发）
     * - B 在补发后再回 ACK_RECEIVED，服务端推进状态为 RECEIVED
     *
     * 备注：
     * - WsCron 当前扫描条件：status=SAVED && createdAt < now-1s && updatedAt < now-ackTimeoutMs
     * - WsCron 调度间隔由配置控制：im.cron.scan-dropped.fixed-delay-ms（默认已调小，便于测试）
     */
    private static Map<String, Object> scenarioCronResend(String tokenA, String tokenB) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", false);
        r.put("why", "验证定时任务补发：SAVED 且未收到 ACK_RECEIVED -> WsCron 补发 -> ACK_RECEIVED 推进状态");
        List<Map<String, Object>> steps = new ArrayList<>();
        r.put("steps", steps);

        HttpClient httpClient = HttpClient.newHttpClient();
        QueueListener listenerA = new QueueListener();
        QueueListener listenerB = new QueueListener();

        WebSocket wsA = null;
        WebSocket wsB = null;
        try {
            wsA = connect(httpClient, tokenA, listenerA);
            wsB = connect(httpClient, tokenB, listenerB);
            steps.add(step("A->S", "AUTH", authJsonMasked(tokenA)));
            auth(wsA, tokenA, listenerA.queue);
            steps.add(step("B->S", "AUTH", authJsonMasked(tokenB)));
            auth(wsB, tokenB, listenerB.queue);

            String clientMsgId = "cron-" + UUID.randomUUID();
            String sendJson = singleChatJson(clientMsgId, USER_B, "hello-cron-resend");
            steps.add(step("A->S", "SINGLE_CHAT send", sendJson));
            sendText(wsA, sendJson);

            String ackSaved = await(listenerA.queue, TIMEOUT_MS, isAckSaved(clientMsgId));
            steps.add(step("S->A", "ACK(saved)", ackSaved));
            String serverMsgId = requireField(ackSaved, "serverMsgId");

            // B 第一次收到（但不回 ACK_RECEIVED）
            String delivered1 = await(listenerB.queue, TIMEOUT_MS, isSingleChat(clientMsgId, serverMsgId));
            steps.add(step("S->B", "SINGLE_CHAT deliver #1 (no ACK_RECEIVED)", delivered1));

            // 等待定时任务补发（给足够窗口：ackTimeout + cron interval + buffer）
            long waitMs = Math.max(TIMEOUT_MS, 20000);
            String delivered2 = await(listenerB.queue, waitMs, isSingleChat(clientMsgId, serverMsgId));
            steps.add(step("S->B", "SINGLE_CHAT deliver #2 (cron resend)", delivered2));

            // 收到补发后再回 ACK_RECEIVED
            String ackReceiveJson = ackReceiveJson(clientMsgId, serverMsgId, USER_A);
            steps.add(step("B->S", "ACK(ack_receive)", ackReceiveJson));
            sendText(wsB, ackReceiveJson);
            Thread.sleep(800);

            r.put("ok", true);
            r.put("clientMsgId", clientMsgId);
            r.put("serverMsgId", serverMsgId);
            r.put("expected", "B 不回 ACK_RECEIVED 时应被 WsCron 补发；回 ACK 后 DB 状态应变为 RECEIVED(5)");
            return r;
        } catch (Exception e) {
            r.put("reason", "常见原因：WsCron 扫描条件过严（ackTimeout/updatedAt）、调度间隔太长、服务端未启动定时任务");
            r.put("error", String.valueOf(e));
            return r;
        } finally {
            close(wsA);
            close(wsB);
        }
    }

    /**
     * 场景 5：鉴权链路
     * 目标：
     * - 握手阶段无 token：应 401 拒绝（missing_access_token）
     * - 握手阶段 token 已过期：应 401 拒绝（invalid_access_token）
     * - 握手 + AUTH 成功后：PING -> PONG
     * - accessToken 过期后：PING -> ERROR(token_expired) 并断开
     */
    private static Map<String, Object> scenarioAuthChain() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", false);
        r.put("why", "验证握手鉴权 + AUTH/REAUTH 续期 + token 过期断连");
        List<Map<String, Object>> steps = new ArrayList<>();
        r.put("steps", steps);

        final long uid = USER_A + 100_000;
        final long handshakeTimeoutMs = Math.min(TIMEOUT_MS, 1500);

        // 1) short-lived token: auth ok -> ping ok -> expire -> reauth -> ping ok -> expire -> ping fail
        HttpClient okClient = HttpClient.newHttpClient();
        QueueListener listener = new QueueListener();
        WebSocket ws = null;
        try {
            String shortToken = issueAccessToken(uid, AUTH_TOKEN_TTL_SECONDS);
            ws = connect(okClient, shortToken, listener);

            Map<String, Object> redis1 = tryReadRedisRoute(uid);
            r.put("redisRouteAfterHandshake", redis1);
            steps.add(step("C->Redis", "GET/TTL route(userA)", toJson(redis1)));

            steps.add(step("C->S", "AUTH", authJsonMasked(shortToken)));
            auth(ws, shortToken, listener.queue);
            steps.add(step("S->C", "AUTH_OK", "{\"type\":\"AUTH_OK\"}"));

            String ping1 = jsonObj("type", "PING", "ts", String.valueOf(Instant.now().toEpochMilli()));
            steps.add(step("C->S", "PING(before exp)", ping1));
            sendText(ws, ping1);
            String pong1 = await(listener.queue, TIMEOUT_MS, s -> "PONG".equalsIgnoreCase(extractString(s, "type")));
            steps.add(step("S->C", "PONG", pong1));

            // 等待 token 过期（服务端按 expMs 判断，单位毫秒）
            Thread.sleep(AUTH_TOKEN_TTL_SECONDS * 1000 + 500);

            // REAUTH：刷新 expMs（不触发离线补发）
            String newToken = issueAccessToken(uid, AUTH_TOKEN_TTL_SECONDS);
            String reauthJson = jsonObj("type", "REAUTH", "token", newToken);
            steps.add(step("C->S", "REAUTH", "{\"type\":\"REAUTH\",\"token\":\"***\"}"));
            sendText(ws, reauthJson);
            String reauthOk = await(listener.queue, TIMEOUT_MS, s -> "AUTH_OK".equalsIgnoreCase(extractString(s, "type")));
            steps.add(step("S->C", "AUTH_OK (reauth)", reauthOk));

            // REAUTH 后应继续可用
            String ping2 = jsonObj("type", "PING", "ts", String.valueOf(Instant.now().toEpochMilli()));
            steps.add(step("C->S", "PING(after reauth)", ping2));
            sendText(ws, ping2);
            String pong2 = await(listener.queue, TIMEOUT_MS, s -> "PONG".equalsIgnoreCase(extractString(s, "type")));
            steps.add(step("S->C", "PONG", pong2));

            // 等待“新 token”过期后，再次 PING 应 token_expired
            Thread.sleep(AUTH_TOKEN_TTL_SECONDS * 1000 + 500);
            String ping3 = jsonObj("type", "PING", "ts", String.valueOf(Instant.now().toEpochMilli()));
            steps.add(step("C->S", "PING(after new exp)", ping3));
            sendText(ws, ping3);
            String err = await(listener.queue, TIMEOUT_MS, s -> "ERROR".equalsIgnoreCase(extractString(s, "type")));
            steps.add(step("S->C", "ERROR(token_expired)", err));

            String reason = extractString(err, "reason");
            Map<String, Object> redis2 = tryReadRedisRoute(uid);
            r.put("redisRouteAfterExpiredPing", redis2);
            steps.add(step("C->Redis", "GET/TTL route(userA) after exp", toJson(redis2)));
            r.put("expected", "before exp: PONG; REAUTH success -> still PONG; after new exp: ERROR(token_expired) then close");
            r.put("errorReason", reason);
            r.put("ok", reason != null && "token_expired".equalsIgnoreCase(reason));
        } catch (Exception e) {
            r.put("reason", "常见原因：服务端未开启握手鉴权/exp 单位不一致/clock skew 太大/WS 未启动");
            r.put("error", String.valueOf(e));
        } finally {
            close(ws);
        }

        // 1.5) reauth uid mismatch (best-effort; does not affect ok)
        try {
            HttpClient c = HttpClient.newHttpClient();
            QueueListener l = new QueueListener();
            WebSocket w = connect(c, issueAccessToken(uid, 30), l);
            auth(w, issueAccessToken(uid, 30), l.queue);

            String mismatchToken = issueAccessToken(uid + 1, 30);
            String reauth = jsonObj("type", "REAUTH", "token", mismatchToken);
            steps.add(step("C->S", "REAUTH(uid mismatch)", "{\"type\":\"REAUTH\",\"token\":\"***\"}"));
            sendText(w, reauth);
            String err = await(l.queue, TIMEOUT_MS, s -> "ERROR".equalsIgnoreCase(extractString(s, "type")));
            steps.add(step("S->C", "ERROR", err));
            close(w);
        } catch (Exception e) {
            steps.add(step("C->S", "REAUTH(uid mismatch)", "{\"type\":\"REAUTH_MISMATCH_SKIP\",\"reason\":" + jsonString(String.valueOf(e)) + "}"));
        }

        // 2) handshake negative tests (best-effort; do not affect ok)
        HttpClient httpClient = HttpClient.newHttpClient();

        try {
            WebSocket noToken = connectNoAuthHeader(httpClient, new QueueListener(), handshakeTimeoutMs);
            close(noToken);
            steps.add(step("C->S", "HANDSHAKE(no token)", "{\"type\":\"__UNEXPECTED__\",\"reason\":\"handshake_succeeded_but_should_fail\"}"));
        } catch (Exception e) {
            steps.add(step("C->S", "HANDSHAKE(no token)", "{\"type\":\"HANDSHAKE_FAIL\",\"reason\":" + jsonString(String.valueOf(e)) + "}"));
        }

        try {
            String expired = issueAccessToken(uid, -1);
            WebSocket expiredWs = connect(httpClient, expired, new QueueListener(), handshakeTimeoutMs);
            close(expiredWs);
            steps.add(step("C->S", "HANDSHAKE(expired token)", "{\"type\":\"__UNEXPECTED__\",\"reason\":\"handshake_succeeded_but_should_fail\"}"));
        } catch (Exception e) {
            steps.add(step("C->S", "HANDSHAKE(expired token)", "{\"type\":\"HANDSHAKE_FAIL\",\"reason\":" + jsonString(String.valueOf(e)) + "}"));
        }

        try {
            QueueListener ql = new QueueListener();
            String token = issueAccessToken(uid);
            WebSocket qws = connectWithQueryToken(httpClient, token, ql, handshakeTimeoutMs);
            steps.add(step("C->S", "HANDSHAKE(query token)", "{\"type\":\"HANDSHAKE_OK\",\"via\":\"query\"}"));
            close(qws);
        } catch (Exception e) {
            steps.add(step("C->S", "HANDSHAKE(query token)", "{\"type\":\"HANDSHAKE_FAIL\",\"reason\":" + jsonString(String.valueOf(e)) + "}"));
        }

        try {
            String invalid = issueAccessToken(uid) + "x";
            WebSocket invalidWs = connect(httpClient, invalid, new QueueListener(), handshakeTimeoutMs);
            close(invalidWs);
            steps.add(step("C->S", "HANDSHAKE(invalid token)", "{\"type\":\"__UNEXPECTED__\",\"reason\":\"handshake_succeeded_but_should_fail\"}"));
        } catch (Exception e) {
            steps.add(step("C->S", "HANDSHAKE(invalid token)", "{\"type\":\"HANDSHAKE_FAIL\",\"reason\":" + jsonString(String.valueOf(e)) + "}"));
        }

        return r;
    }

    private static WebSocket connect(HttpClient httpClient, String token, QueueListener listener) {
        return connect(httpClient, token, listener, TIMEOUT_MS);
    }

    private static WebSocket connect(HttpClient httpClient, String token, QueueListener listener, long timeoutMs) {
        // 注意：Java HttpClient 的 WebSocketBuilder 在某些环境不允许自定义握手 headers（但 JDK22 正常）
        // 如果握手返回 401，通常是：
        // - jwtSecret 不一致
        // - iat/exp 单位不一致（服务端要求秒，这里按秒生成）
        // - token 类型 typ 不是 access
        return httpClient.newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .buildAsync(URI.create(WS_URL), listener)
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .join();
    }

    private static WebSocket connectNoAuthHeader(HttpClient httpClient, QueueListener listener) {
        return connectNoAuthHeader(httpClient, listener, TIMEOUT_MS);
    }

    private static WebSocket connectNoAuthHeader(HttpClient httpClient, QueueListener listener, long timeoutMs) {
        return httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(WS_URL), listener)
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .join();
    }

    private static WebSocket connectWithQueryToken(HttpClient httpClient, String token, QueueListener listener) {
        return connectWithQueryToken(httpClient, token, listener, TIMEOUT_MS);
    }

    private static WebSocket connectWithQueryToken(HttpClient httpClient, String token, QueueListener listener, long timeoutMs) {
        String sep = WS_URL.contains("?") ? "&" : "?";
        String url = WS_URL + sep + "token=" + urlEncode(token);
        return httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(url), listener)
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .join();
    }

    private static void auth(WebSocket ws, String token, BlockingQueue<String> inbox) throws Exception {
        sendText(ws, jsonObj(
                "type", "AUTH",
                "token", token
        ));

        // 只要出现 AUTH_OK 即代表“已绑定 userId 且 token 解析成功”
        await(inbox, TIMEOUT_MS, s -> "AUTH_OK".equalsIgnoreCase(extractString(s, "type")));
    }

    private static void sendText(WebSocket ws, String json) {
        ws.sendText(json, true)
                .orTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .join();
    }

    private static String authJson(String token) {
        return jsonObj("type", "AUTH", "token", token);
    }

    private static String authJsonMasked(String token) {
        return jsonObj("type", "AUTH", "token", maskToken(token));
    }

    private static String maskToken(String token) {
        if (token == null) return null;
        int len = token.length();
        if (len <= 12) return "***";
        return token.substring(0, 6) + "..." + token.substring(len - 4);
    }

    private static String redactTokenInRawJson(String rawJson) {
        if (rawJson == null) return null;
        String needle = "\"token\":\"";
        int idx = rawJson.indexOf(needle);
        if (idx < 0) return rawJson;
        int start = idx + needle.length();
        int end = rawJson.indexOf('"', start);
        if (end < 0) return rawJson;
        return rawJson.substring(0, start) + "***" + rawJson.substring(end);
    }

    private static String singleChatJson(String clientMsgId, long toUserId, String body) {
        return jsonObj(
                "type", "SINGLE_CHAT",
                "clientMsgId", clientMsgId,
                "to", String.valueOf(toUserId),
                "msgType", "TEXT",
                "body", body,
                "ts", String.valueOf(Instant.now().toEpochMilli())
        );
    }

    private static String ackReceiveJson(String clientMsgId, String serverMsgId, long toUserId) {
        // ackType 支持 received / ack_receive（服务端做了 ignoreCase）
        return jsonObj(
                "type", "ACK",
                "clientMsgId", clientMsgId,
                "serverMsgId", serverMsgId,
                "to", String.valueOf(toUserId),
                "ackType", "ack_receive",
                "ts", String.valueOf(Instant.now().toEpochMilli())
        );
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

    private static Map<String, Object> step(String direction, String step, String rawJson) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("atMs", Instant.now().toEpochMilli());
        m.put("direction", direction);
        m.put("step", step);
        String safeRawJson = redactTokenInRawJson(rawJson);
        m.put("type", extractString(safeRawJson, "type"));
        m.put("clientMsgId", extractString(safeRawJson, "clientMsgId"));
        m.put("serverMsgId", extractString(safeRawJson, "serverMsgId"));
        m.put("ackType", extractString(safeRawJson, "ackType"));
        m.put("body", extractString(safeRawJson, "body"));
        m.put("raw", safeRawJson);
        return m;
    }

    private static void close(WebSocket ws) {
        if (ws == null) return;
        try {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
                    .orTimeout(800, TimeUnit.MILLISECONDS)
                    .join();
        } catch (Exception ignored) {
        }
        try {
            ws.abort();
        } catch (Exception ignored) {
        }
    }

    private static String issueAccessToken(long userId) {
        return issueAccessToken(userId, 1800);
    }

    private static String issueAccessToken(long userId, long ttlSeconds) {
        // 与服务端 JwtService 一致：iat/exp 为秒（Date.from(Instant)）
        long now = Instant.now().getEpochSecond();
        long exp = now + ttlSeconds;

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

    private static String urlEncode(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    sb.append('%');
                    String hex = Integer.toHexString(b & 0xff).toUpperCase();
                    if (hex.length() == 1) sb.append('0');
                    sb.append(hex);
                }
            }
        }
        return sb.toString();
    }

    private static Map<String, Object> tryReadRedisRoute(long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("host", REDIS_HOST);
        out.put("port", REDIS_PORT);
        out.put("key", "im:gw:route:" + userId);

        try (RedisRespClient client = new RedisRespClient(REDIS_HOST, REDIS_PORT, 800)) {
            if (REDIS_PASSWORD != null && !REDIS_PASSWORD.isBlank()) {
                client.auth(REDIS_PASSWORD);
            }
            String key = (String) out.get("key");
            out.put("value", client.get(key));
            out.put("ttlSeconds", client.ttl(key));
            out.put("ok", true);
            return out;
        } catch (Exception e) {
            out.put("error", String.valueOf(e));
            return out;
        }
    }

    static class RedisRespClient implements AutoCloseable {
        private final java.net.Socket socket;
        private final java.io.InputStream in;
        private final java.io.OutputStream out;

        RedisRespClient(String host, int port, int connectTimeoutMs) throws Exception {
            this.socket = new java.net.Socket();
            this.socket.connect(new java.net.InetSocketAddress(host, port), connectTimeoutMs);
            this.socket.setSoTimeout(1500);
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }

        void auth(String password) throws Exception {
            Object resp = send("AUTH", password);
            if (!(resp instanceof String s) || (!"OK".equalsIgnoreCase(s))) {
                throw new RuntimeException("redis auth failed: " + resp);
            }
        }

        String get(String key) throws Exception {
            Object resp = send("GET", key);
            if (resp == null) return null;
            if (resp instanceof String s) return s;
            throw new RuntimeException("unexpected GET resp: " + resp);
        }

        long ttl(String key) throws Exception {
            Object resp = send("TTL", key);
            if (resp instanceof Long l) return l;
            throw new RuntimeException("unexpected TTL resp: " + resp);
        }

        private Object send(String... args) throws Exception {
            writeCommand(args);
            out.flush();
            return readResp();
        }

        private void writeCommand(String... args) throws Exception {
            out.write('*');
            out.write(Integer.toString(args.length).getBytes(StandardCharsets.US_ASCII));
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            for (String a : args) {
                byte[] bytes = a.getBytes(StandardCharsets.UTF_8);
                out.write('$');
                out.write(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.write(bytes);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            }
        }

        private Object readResp() throws Exception {
            int b = in.read();
            if (b < 0) throw new RuntimeException("redis eof");
            switch (b) {
                case '+': return readLine();
                case '-': throw new RuntimeException("redis error: " + readLine());
                case ':': return Long.parseLong(readLine());
                case '$': {
                    int len = Integer.parseInt(readLine());
                    if (len < 0) return null;
                    byte[] buf = in.readNBytes(len);
                    in.readNBytes(2); // CRLF
                    return new String(buf, StandardCharsets.UTF_8);
                }
                default: throw new RuntimeException("redis bad resp: " + (char) b);
            }
        }

        private String readLine() throws Exception {
            StringBuilder sb = new StringBuilder();
            while (true) {
                int c = in.read();
                if (c < 0) throw new RuntimeException("redis eof");
                if (c == '\r') {
                    int n = in.read();
                    if (n != '\n') throw new RuntimeException("redis bad crlf");
                    return sb.toString();
                }
                sb.append((char) c);
            }
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }
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
        if (v instanceof Iterable<?> it) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(',');
                first = false;
                sb.append(toJsonValue(o));
            }
            sb.append(']');
            return sb.toString();
        }
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
