package com.miniim.gateway.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniim.auth.service.SessionVersionStore;
import com.miniim.gateway.session.SessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

@Slf4j
public class WsFrameHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final long SV_REVALIDATE_MIN_INTERVAL_MS = 3_000;

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
            return;
        }

        if (msg.type == null) {
            wsWriter.writeError(ctx, "missing_type", null, null);
            return;
        }

        // 除了 AUTH/REAUTH 以外，其他消息都要求：已鉴权且 accessToken 未过期。
        if (!"AUTH".equals(msg.type) && !"REAUTH".equals(msg.type)) {
            if (!sessionRegistry.isAuthed(ctx.channel())) {
                wsWriter.writeError(ctx, "unauthorized", msg.getClientMsgId(), msg.getServerMsgId());
                ctx.close();
                return;
            }
            if (isExpired(ctx)) {
                wsWriter.writeError(ctx, "token_expired", msg.getClientMsgId(), msg.getServerMsgId());
                ctx.close();
                return;
            }
            if (!revalidateSessionVersionIfNeeded(ctx, msg)) {
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
