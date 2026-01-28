package com.miniim.gateway.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniim.auth.service.SessionVersionStore;
import com.miniim.gateway.session.SessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

@Slf4j
public class WsFrameHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    /**
     * sessionVersion 复验最小间隔：用于把“控制面最终必生效”做成连接存活期的硬门禁，
     * 但避免每条业务消息都打 Redis（限频复验）。
     */
    private static final long SV_REVALIDATE_MIN_INTERVAL_MS = 3_000;

    /**
     * 协议违规窗口：在一个短窗口内多次发送非法包（bad_json/missing_type/unknown_type）则断开连接。
     * <p>
     * 目的：防刷包、防日志放大、保护 objectMapper 解析与业务线程池。
     */
    private static final long BAD_JSON_WINDOW_MS = 10_000;
    private static final int BAD_JSON_MAX_IN_WINDOW = 3;

    /**
     * 连接级限流：只看 TextWebSocketFrame 的到达速率，保护 JSON 解析与日志。
     * <p>
     * 注意：这是“网关保护阈值”，不是业务语义。
     */
    private static final int CONN_RATE_PER_SEC = 60;
    private static final int CONN_RATE_BURST = 120;

    /**
     * 用户级限流：对业务类消息限频，保护 DB/缓存/群发 fanout。
     * <p>
     * 不对 AUTH/REAUTH/PING/PONG 做限流：避免弱网重连/续期/心跳被误伤。
     */
    private static final int USER_RATE_PER_SEC = 30;
    private static final int USER_RATE_BURST = 60;

    private static final AttributeKey<TokenBucket> ATTR_CONN_BUCKET = AttributeKey.valueOf("im_ws_conn_bucket");
    private static final AttributeKey<ViolationWindow> ATTR_BAD_JSON_WINDOW = AttributeKey.valueOf("im_ws_bad_json");
    private static final AttributeKey<TokenBucket> ATTR_USER_BUCKET = AttributeKey.valueOf("im_ws_user_bucket");

    private final ObjectMapper objectMapper;
    private final SessionRegistry sessionRegistry;
    private final WsWriter wsWriter;
    private final WsAuthHandler wsAuthHandler;
    private final WsPingHandler wsPingHandler;
    private final SessionVersionStore sessionVersionStore;
    private final WsCallHandler wsCallHandler;
    private final WsAckHandler wsAckHandler;
    private final WsFriendRequestHandler wsFriendRequestHandler;
    private final WsSingleChatHandler wsSingleChatHandler;
    private final WsGroupChatHandler wsGroupChatHandler;
    private final WsMessageRevokeHandler wsMessageRevokeHandler;
    public WsFrameHandler(ObjectMapper objectMapper,
                          SessionRegistry sessionRegistry,
                          WsWriter wsWriter,
                          WsAuthHandler wsAuthHandler,
                          WsPingHandler wsPingHandler,
                          SessionVersionStore sessionVersionStore,
                          WsCallHandler wsCallHandler,
                          WsAckHandler wsAckHandler,
                          WsFriendRequestHandler wsFriendRequestHandler,
                          WsSingleChatHandler wsSingleChatHandler,
                          WsGroupChatHandler wsGroupChatHandler,
                          WsMessageRevokeHandler wsMessageRevokeHandler) {
        this.objectMapper = objectMapper;
        this.sessionRegistry = sessionRegistry;
        this.wsWriter = wsWriter;
        this.wsAuthHandler = wsAuthHandler;
        this.wsPingHandler = wsPingHandler;
        this.sessionVersionStore = sessionVersionStore;
        this.wsCallHandler = wsCallHandler;
        this.wsAckHandler = wsAckHandler;
        this.wsFriendRequestHandler = wsFriendRequestHandler;
        this.wsSingleChatHandler = wsSingleChatHandler;
        this.wsGroupChatHandler = wsGroupChatHandler;
        this.wsMessageRevokeHandler = wsMessageRevokeHandler;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) throws Exception {
        // 连接级限流：最先执行，避免恶意/异常连接刷爆解析和日志。
        if (!consumeConnToken(ctx)) {
            wsWriter.writeError(ctx, "too_many_requests", null, null);
            ctx.close();
            return;
        }

        // 我们约定：客户端发来的每一个 TextWebSocketFrame 都是一段 JSON
        // 用 WsEnvelope 这个“信封对象”承载：type + token + from/to + body + ts ...
        String raw = frame.text();
        WsEnvelope msg;
        try {
            msg = objectMapper.readValue(raw, WsEnvelope.class);
            String type = msg.getType();
            if (type != null && type.startsWith("CALL_")) {
                Long uid = ctx.channel().attr(SessionRegistry.ATTR_USER_ID).get();
                log.info("received call envelope type={}, userId={}, callId={}", type, uid, msg.getCallId());
            } else {
                log.debug("received frame {}", redactToken(raw));
                if ("PING".equals(type) || "AUTH".equals(type) || "REAUTH".equals(type)) {
                    log.debug("received envelope type={}, clientMsgId={}, serverMsgId={}, from={}, to={}",
                            type, msg.getClientMsgId(), msg.getServerMsgId(), msg.getFrom(), msg.getTo());
                } else {
                    log.info("received envelope type={}, clientMsgId={}, serverMsgId={}, from={}, to={}",
                            type, msg.getClientMsgId(), msg.getServerMsgId(), msg.getFrom(), msg.getTo());
                }
            }
        } catch (Exception e) {
            wsWriter.writeError(ctx, "bad_json", null, null);
            // 解析失败也要计入违规：短时间多次 bad_json 直接断开。
            if (!recordBadJsonAndShouldKeep(ctx)) {
                ctx.close();
            }
            return;
        }

        if (msg.type == null) {
            wsWriter.writeError(ctx, "missing_type", null, null);
            // 缺字段属于协议违规：短时间多次直接断开。
            if (!recordBadJsonAndShouldKeep(ctx)) {
                ctx.close();
            }
            return;
        }

        Channel ch = ctx.channel();
        boolean authed = sessionRegistry.isAuthed(ch);

        // AUTH-first：未鉴权只允许 AUTH / PING / PONG；其他一律 ERROR unauthorized 并断连。
        if (!authed) {
            if (!isPreAuthAllowed(msg.type)) {
                wsWriter.writeError(ctx, "unauthorized", msg.getClientMsgId(), msg.getServerMsgId());
                ctx.close();
                return;
            }
        } else {
            // 已鉴权：业务消息要求 accessToken 未过期 + sessionVersion 复验通过（限频）。
            if (!"AUTH".equals(msg.type) && !"REAUTH".equals(msg.type) && !"PING".equals(msg.type) && !"PONG".equals(msg.type)) {
                if (isExpired(ctx)) {
                    wsWriter.writeError(ctx, "token_expired", msg.getClientMsgId(), msg.getServerMsgId());
                    ctx.close();
                    return;
                }
                if (!revalidateSessionVersionIfNeeded(ctx, msg)) {
                    return;
                }
            }
        }

        // 业务类消息做用户级限流；心跳/鉴权/续期不参与（避免误伤弱网重连/续期）。
        if (shouldApplyUserLimit(msg.type) && authed) {
            if (!consumeUserToken(ctx)) {
                wsWriter.writeError(ctx, "too_many_requests", msg.getClientMsgId(), msg.getServerMsgId());
                ctx.close();
                return;
            }
        }

        switch (msg.type) {
            case "AUTH" -> wsAuthHandler.handleAuth(ctx, msg);
            case "REAUTH" -> wsAuthHandler.handleReauth(ctx, msg);
            case "PING" -> wsPingHandler.handleClientPing(ctx);
            case "PONG" -> sessionRegistry.touch(ctx.channel());
            case "SINGLE_CHAT" -> wsSingleChatHandler.handle(ctx, msg);
            case "GROUP_CHAT" -> wsGroupChatHandler.handle(ctx, msg);
            case "FRIEND_REQUEST" -> wsFriendRequestHandler.handle(ctx, msg);
            case "ACK" -> wsAckHandler.handle(ctx, msg);
            case "MESSAGE_REVOKE" -> wsMessageRevokeHandler.handle(ctx, msg);
            case "CALL_INVITE" -> wsCallHandler.handleInvite(ctx, msg);
            case "CALL_ACCEPT" -> wsCallHandler.handleAccept(ctx, msg);
            case "CALL_REJECT" -> wsCallHandler.handleReject(ctx, msg);
            case "CALL_CANCEL" -> wsCallHandler.handleCancel(ctx, msg);
            case "CALL_END" -> wsCallHandler.handleEnd(ctx, msg);
            case "CALL_ICE" -> wsCallHandler.handleIce(ctx, msg);
            default -> {
                wsWriter.writeError(ctx, "not_implemented", msg.getClientMsgId(), null);
                // 未实现的 type 同样计入协议违规：避免客户端 bug/探测刷爆网关。
                if (!recordBadJsonAndShouldKeep(ctx)) {
                    ctx.close();
                }
            }
        }
    }

    private static String redactToken(String raw) {
        if (raw == null) {
            return null;
        }
        String needle = "\"token\":\"";
        int idx = raw.indexOf(needle);
        if (idx < 0) {
            return raw;
        }
        int start = idx + needle.length();
        int end = raw.indexOf('"', start);
        if (end < 0) {
            return raw;
        }
        return raw.substring(0, start) + "***" + raw.substring(end);
    }

    private boolean isExpired(ChannelHandlerContext ctx) {
        return isExpired(ctx.channel());
    }

    private boolean isExpired(Channel ch) {
        Long expMs = sessionRegistry.getAccessExpMs(ch);
        return expMs != null && Instant.now().toEpochMilli() >= expMs;
    }

    private static boolean shouldApplyUserLimit(String type) {
        if (type == null) {
            return true;
        }
        return !("PING".equals(type) || "PONG".equals(type) || "AUTH".equals(type) || "REAUTH".equals(type));
    }

    private static boolean isPreAuthAllowed(String type) {
        return "AUTH".equals(type) || "PING".equals(type) || "PONG".equals(type);
    }

    private static boolean recordBadJsonAndShouldKeep(ChannelHandlerContext ctx) {
        Channel ch = ctx.channel();
        long now = System.currentTimeMillis();
        ViolationWindow win = ch.attr(ATTR_BAD_JSON_WINDOW).get();
        if (win == null || now - win.windowStartMs >= BAD_JSON_WINDOW_MS) {
            win = new ViolationWindow(now, 0);
        }
        win.count++;
        ch.attr(ATTR_BAD_JSON_WINDOW).set(win);
        return win.count <= BAD_JSON_MAX_IN_WINDOW;
    }

    private static boolean consumeConnToken(ChannelHandlerContext ctx) {
        return consumeToken(ctx.channel(), ATTR_CONN_BUCKET, CONN_RATE_PER_SEC, CONN_RATE_BURST);
    }

    private static boolean consumeUserToken(ChannelHandlerContext ctx) {
        return consumeToken(ctx.channel(), ATTR_USER_BUCKET, USER_RATE_PER_SEC, USER_RATE_BURST);
    }

    private static boolean consumeToken(Channel ch,
                                        AttributeKey<TokenBucket> key,
                                        int ratePerSec,
                                        int burst) {
        if (ch == null) {
            return false;
        }
        // 用 nanoTime 计算时间差，避免系统时间调整导致的突刺/负值。
        long nowNs = System.nanoTime();
        TokenBucket b = ch.attr(key).get();
        if (b == null) {
            b = new TokenBucket(burst, nowNs);
        } else {
            refill(b, nowNs, ratePerSec, burst);
        }
        if (b.tokens <= 0) {
            ch.attr(key).set(b);
            return false;
        }
        b.tokens--;
        ch.attr(key).set(b);
        return true;
    }

    private static void refill(TokenBucket b, long nowNs, int ratePerSec, int burst) {
        long elapsedNs = nowNs - b.lastRefillNs;
        if (elapsedNs <= 0) {
            return;
        }
        // 按固定速率补充 token，最大不超过 burst。
        long add = (elapsedNs * (long) ratePerSec) / 1_000_000_000L;
        if (add <= 0) {
            return;
        }
        b.tokens = (int) Math.min(burst, (long) b.tokens + add);
        // 把“已经折算成 token 的时间”从 lastRefillNs 中扣掉，减少累计误差。
        long consumedNs = (add * 1_000_000_000L) / Math.max(1L, ratePerSec);
        b.lastRefillNs += consumedNs;
    }

    private static final class TokenBucket {
        int tokens;
        long lastRefillNs;

        TokenBucket(int tokens, long lastRefillNs) {
            this.tokens = tokens;
            this.lastRefillNs = lastRefillNs;
        }
    }

    private static final class ViolationWindow {
        final long windowStartMs;
        int count;

        ViolationWindow(long windowStartMs, int count) {
            this.windowStartMs = windowStartMs;
            this.count = count;
        }
    }

    /**
     * 连接存活期间的“轻量硬校验”
     * - 按连接限频复验 sessionVersion，降低 Redis 压力
     * - Redis 异常时 SessionVersionStore 内部 fail-open
     */
    private boolean revalidateSessionVersionIfNeeded(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel ch = ctx.channel();
        Long userId = ch.attr(SessionRegistry.ATTR_USER_ID).get();
        Long tokenSv = ch.attr(SessionRegistry.ATTR_SESSION_VERSION).get();
        if (userId == null || tokenSv == null) {
            return true;
        }

        long now = System.currentTimeMillis();
        Long last = ch.attr(SessionRegistry.ATTR_LAST_SV_CHECK_MS).get();
        if (last != null && now - last < SV_REVALIDATE_MIN_INTERVAL_MS) {
            return true;
        }
        ch.attr(SessionRegistry.ATTR_LAST_SV_CHECK_MS).set(now);

        if (sessionVersionStore.isValid(userId, tokenSv)) {
            return true;
        }
        wsWriter.writeError(ctx, "session_invalid", msg.getClientMsgId(), msg.getServerMsgId());
        ctx.close();
        return false;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent e && e.state() == IdleState.READER_IDLE) {
            // READER_IDLE：一段时间没有收到任何数据。
            // 这通常意味着：
            // - 客户端断网了但 TCP 没及时感知
            // - 客户端异常退出
            // - 客户端没按约定发心跳
            // 我们的处理策略：解绑会话并关闭连接。
            sessionRegistry.unbind(ctx.channel());
            ctx.close();
        }
        if (evt instanceof IdleStateEvent e && e.state() == IdleState.WRITER_IDLE) {
            // 在线状态依赖 Redis routeKey TTL；如果客户端长期不发 PING，TTL 会过期导致“在线/离线抖动”。
            // 这里在服务端心跳写出时也刷新 TTL，避免仅靠客户端心跳。
            wsPingHandler.onWriterIdle(ctx);
            //再次检测
//               sessionRegistry.unbind(ctx.channel());
//                ctx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Long userId = ctx.channel().attr(SessionRegistry.ATTR_USER_ID).get();
        wsCallHandler.onChannelInactive(userId);
        sessionRegistry.unbind(ctx.channel());
    }
    public void channelActive(ChannelHandlerContext ctx) {
        log.info(ctx.channel() + " connected");

    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        sessionRegistry.unbind(ctx.channel());
        ctx.close();
    }
}
