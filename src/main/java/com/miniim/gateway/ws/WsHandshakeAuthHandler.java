package com.miniim.gateway.ws;

import com.miniim.auth.service.JwtService;
import com.miniim.auth.service.SessionVersionStore;
import com.miniim.gateway.session.SessionRegistry;
import com.miniim.gateway.session.WsRouteStore;
import com.miniim.gateway.ws.cluster.WsClusterBus;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.util.List;
import java.util.Map;
import java.time.Duration;

/**
 * WebSocket 握手阶段（HTTP Upgrade）鉴权：
 * <ul>
 *   <li>从 Authorization: Bearer <token> 或 query 参数 token/accessToken 里取 accessToken</li>
 *   <li>校验 accessToken 并解析 userId/exp</li>
 *   <li>把 userId 与过期时间绑定到 channel</li>
 * </ul>
 */
public class WsHandshakeAuthHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final String wsPath;
    private final JwtService jwtService;
    private final SessionVersionStore sessionVersionStore;
    private final SessionRegistry sessionRegistry;
    private final WsRouteStore routeStore;
    private final WsClusterBus clusterBus;
    private static final Duration ROUTE_TTL = Duration.ofSeconds(120);

    public WsHandshakeAuthHandler(
            String wsPath,
            JwtService jwtService,
            SessionVersionStore sessionVersionStore,
            SessionRegistry sessionRegistry,
            WsRouteStore routeStore,
            WsClusterBus clusterBus
    ) {
        this.wsPath = wsPath;
        this.jwtService = jwtService;
        this.sessionVersionStore = sessionVersionStore;
        this.sessionRegistry = sessionRegistry;
        this.routeStore = routeStore;
        this.clusterBus = clusterBus;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        // 只处理 WS 握手对应的 upgrade 请求；其他 HTTP 请求（如果未来有）放行。
        String uri = req.uri();
        if (uri == null || !uri.startsWith(wsPath)) {
            ctx.fireChannelRead(req.retain());
            return;
        }

        // 如果已经鉴权过（例如重复握手/异常场景），直接放行。
        if (sessionRegistry.isAuthed(ctx.channel())) {
            ctx.fireChannelRead(req.retain());
            return;
        }

        String token = extractAccessToken(req);
        if (token == null || token.isBlank()) {
            writeUnauthorizedAndClose(ctx, "missing_access_token");
            return;
        }

        try {
            Jws<Claims> jws = jwtService.parseAccessToken(token);
            Claims claims = jws.getPayload();

            long userId = jwtService.getUserId(claims);
            long sv = jwtService.getSessionVersion(claims);
            if (!sessionVersionStore.isValid(userId, sv)) {
                writeUnauthorizedAndClose(ctx, "session_invalid");
                return;
            }
            Long expMs = claims.getExpiration() == null ? null : claims.getExpiration().getTime();

            sessionRegistry.bind(ctx.channel(), userId, expMs);
            afterAuthed(userId, ctx);
            ctx.fireChannelRead(req.retain());
        } catch (Exception e) {
            writeUnauthorizedAndClose(ctx, "invalid_access_token");
        }
    }

    private void afterAuthed(long userId, ChannelHandlerContext ctx) {
        if (userId <= 0 || ctx == null || ctx.channel() == null) {
            return;
        }
        String connId = ctx.channel().attr(SessionRegistry.ATTR_CONN_ID).get();
        if (connId == null || connId.isBlank()) {
            return;
        }
        WsRouteStore.RouteInfo old = routeStore.setAndGetOld(userId, connId, ROUTE_TTL);
        sessionRegistry.closeOtherChannels(userId, ctx.channel());
        if (old == null || old.serverId() == null || old.serverId().isBlank()) {
            return;
        }
        if (routeStore.serverId().equals(old.serverId())) {
            if (old.connId() != null && !old.connId().isBlank() && !connId.equals(old.connId())) {
                sessionRegistry.closeByConnId(userId, old.connId());
            }
            return;
        }
        clusterBus.publishKick(old.serverId(), userId, old.connId(), "handshake_auth");
    }

    private String extractAccessToken(FullHttpRequest req) {
        String auth = req.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring("Bearer ".length()).trim();
        }

        QueryStringDecoder decoder = new QueryStringDecoder(req.uri());
        Map<String, List<String>> params = decoder.parameters();

        String fromToken = first(params, "token");
        if (fromToken != null && !fromToken.isBlank()) {
            return fromToken;
        }
        String fromAccessToken = first(params, "accessToken");
        if (fromAccessToken != null && !fromAccessToken.isBlank()) {
            return fromAccessToken;
        }
        return null;
    }

    private String first(Map<String, List<String>> params, String key) {
        List<String> list = params.get(key);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    private void writeUnauthorizedAndClose(ChannelHandlerContext ctx, String reason) {
        byte[] bytes = reason.getBytes(CharsetUtil.UTF_8);
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.UNAUTHORIZED,
                Unpooled.wrappedBuffer(bytes)
        );
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        ctx.writeAndFlush(resp);
        ctx.close();
    }
}
