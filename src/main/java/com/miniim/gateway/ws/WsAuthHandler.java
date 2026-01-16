package com.miniim.gateway.ws;

import com.miniim.auth.service.JwtService;
import com.miniim.auth.service.SessionVersionStore;
import com.miniim.gateway.config.WsResendProperties;
import com.miniim.gateway.session.SessionRegistry;
import com.miniim.gateway.session.WsRouteStore;
import com.miniim.gateway.ws.cluster.WsClusterBus;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * WS 认证处理：
 * - AUTH：绑定 userId/expMs，触发离线补发（同一连接仅一次）
 * - REAUTH：刷新 expMs，不触发离线补发
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WsAuthHandler {

    private static final AttributeKey<Boolean> ATTR_RESEND_AFTER_AUTH_DONE = AttributeKey.valueOf("im_resend_after_auth_done");
    private static final Duration ROUTE_TTL = Duration.ofSeconds(120);

    private final JwtService jwtService;
    private final SessionVersionStore sessionVersionStore;
    private final SessionRegistry sessionRegistry;
    private final WsRouteStore routeStore;
    private final WsClusterBus clusterBus;
    private final WsResendService wsResendService;
    private final WsResendProperties resendProperties;
    private final WsWriter wsWriter;

    public void handleAuth(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel ch = ctx.channel();

        if (sessionRegistry.isAuthed(ch)) {
            Long uid = ch.attr(SessionRegistry.ATTR_USER_ID).get();
            wsWriter.write(ctx, authOk(uid == null ? -1 : uid));
            if (uid != null) {
                afterAuthed(uid, ch, "auth_already_authed");
            }
            if (uid != null && resendProperties.afterAuthEnabledEffective() && markResendAfterAuthOnce(ch)) {
                wsResendService.resendForChannelAsync(ch, uid, "auth_already_authed");
            }
            return;
        }

        if (msg.token == null || msg.token.isBlank()) {
            wsWriter.write(ctx, authFail("missing_token"));
            ctx.close();
            return;
        }

        Long userId;
        try {
            Jws<Claims> jws = jwtService.parseAccessToken(msg.token);
            userId = jwtService.getUserId(jws.getPayload());
            long sv = jwtService.getSessionVersion(jws.getPayload());
            if (!sessionVersionStore.isValid(userId, sv)) {
                wsWriter.write(ctx, authFail("session_invalid"));
                ctx.close();
                return;
            }
            Long expMs = jws.getPayload().getExpiration() == null ? null : jws.getPayload().getExpiration().getTime();
            sessionRegistry.bind(ch, userId, expMs, sv);
            wsWriter.write(ctx, authOk(userId));
        } catch (Exception e) {
            wsWriter.write(ctx, authFail("invalid_token"));
            ctx.close();
            return;
        }

        afterAuthed(userId, ch, "auth");
        if (resendProperties.afterAuthEnabledEffective() && markResendAfterAuthOnce(ch)) {
            wsResendService.resendForChannelAsync(ch, userId, "auth");
        }
    }

    /**
     * reauth：在连接不断开的情况下刷新 accessToken 过期时间。
     *
     * <p>约束：</p>
     * <ul>
     *   <li>必须已绑定 userId（握手鉴权或 AUTH 已完成）</li>
     *   <li>新 token 的 uid 必须与当前连接一致</li>
     *   <li>成功后仅刷新 expMs，不触发离线补发</li>
     * </ul>
     */
    public void handleReauth(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel ch = ctx.channel();
        Long boundUid = ch.attr(SessionRegistry.ATTR_USER_ID).get();
        if (boundUid == null) {
            wsWriter.writeError(ctx, "unauthorized", msg.getClientMsgId(), msg.getServerMsgId());
            ctx.close();
            return;
        }
        if (msg.token == null || msg.token.isBlank()) {
            wsWriter.write(ctx, authFail("missing_token"));
            ctx.close();
            return;
        }

        try {
            Jws<Claims> jws = jwtService.parseAccessToken(msg.token);
            long uid = jwtService.getUserId(jws.getPayload());
            if (!boundUid.equals(uid)) {
                wsWriter.writeError(ctx, "reauth_uid_mismatch", msg.getClientMsgId(), msg.getServerMsgId());
                ctx.close();
                return;
            }
            long sv = jwtService.getSessionVersion(jws.getPayload());
            if (!sessionVersionStore.isValid(uid, sv)) {
                wsWriter.writeError(ctx, "session_invalid", msg.getClientMsgId(), msg.getServerMsgId());
                ctx.close();
                return;
            }

            Long expMs = jws.getPayload().getExpiration() == null ? null : jws.getPayload().getExpiration().getTime();
            sessionRegistry.bind(ch, uid, expMs, sv);
            wsWriter.write(ctx, authOk(uid));
        } catch (Exception e) {
            wsWriter.write(ctx, authFail("invalid_token"));
            ctx.close();
        }
    }

    private void afterAuthed(long userId, Channel ch, String reason) {
        if (userId <= 0 || ch == null) {
            return;
        }
        String connId = ch.attr(SessionRegistry.ATTR_CONN_ID).get();
        if (connId == null || connId.isBlank()) {
            return;
        }

        WsRouteStore.RouteInfo old = routeStore.setAndGetOld(userId, connId, ROUTE_TTL);
        sessionRegistry.closeOtherChannels(userId, ch);

        if (old == null || old.serverId() == null || old.serverId().isBlank()) {
            return;
        }
        if (routeStore.serverId().equals(old.serverId())) {
            if (old.connId() != null && !old.connId().isBlank() && !connId.equals(old.connId())) {
                sessionRegistry.closeByConnId(userId, old.connId());
            }
            return;
        }
        clusterBus.publishKick(old.serverId(), userId, old.connId(), reason);
    }

    private boolean markResendAfterAuthOnce(Channel ch) {
        if (ch == null) {
            return false;
        }
        Boolean done = ch.attr(ATTR_RESEND_AFTER_AUTH_DONE).get();
        if (Boolean.TRUE.equals(done)) {
            return false;
        }
        ch.attr(ATTR_RESEND_AFTER_AUTH_DONE).set(true);
        return true;
    }

    private static WsEnvelope authOk(long userId) {
        WsEnvelope ok = new WsEnvelope();
        ok.type = "AUTH_OK";
        ok.from = userId;
        ok.ts = System.currentTimeMillis();
        return ok;
    }

    private static WsEnvelope authFail(String reason) {
        WsEnvelope fail = new WsEnvelope();
        fail.type = "AUTH_FAIL";
        fail.reason = reason;
        fail.ts = System.currentTimeMillis();
        return fail;
    }
}
