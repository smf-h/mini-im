package com.miniim.auth.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniim.auth.service.JwtService;
import com.miniim.auth.service.SessionVersionStore;
import com.miniim.common.api.ApiCodes;
import com.miniim.common.api.Result;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AccessTokenInterceptor implements HandlerInterceptor {

    public static final String REQ_ATTR_USER_ID = "X-Auth-UserId";

    private static final Logger log = LoggerFactory.getLogger(AccessTokenInterceptor.class);

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;
    private final SessionVersionStore sessionVersionStore;

    /**
     * 这是一个“轻量鉴权拦截器”：
     * <ul>
     *   <li>如果请求没有 Authorization 头：直接放行（不强制登录）</li>
     *   <li>如果带了 Bearer token：尝试解析，把 userId 放到 request attribute 与 ThreadLocal</li>
     *   <li>如果 token 无效：返回 401，并用统一 Result JSON 输出</li>
     * </ul>
     *
     * <p>为什么先不强制？因为我们还没把“哪些接口必须登录”这套权限策略做完。
     * 等你开始做消息/群管理接口时，可以在 WebMvcAuthConfig 按路径强制，或者改为：没有 token 也 401。</p>
     */
    public AccessTokenInterceptor(JwtService jwtService, ObjectMapper objectMapper, SessionVersionStore sessionVersionStore) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
        this.sessionVersionStore = sessionVersionStore;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return true; // 先不强制；业务接口需要时再做强制校验
        }

        String token = header.substring("Bearer ".length()).trim();
        try {
            Jws<Claims> jws = jwtService.parseAccessToken(token);
            long userId = jwtService.getUserId(jws.getPayload());
            long sv = jwtService.getSessionVersion(jws.getPayload());
            if (!sessionVersionStore.isValid(userId, sv)) {
                response.setStatus(401);
                response.setCharacterEncoding("UTF-8");
                response.setContentType("application/json;charset=UTF-8");
                try {
                    String json = objectMapper.writeValueAsString(Result.fail(ApiCodes.UNAUTHORIZED, "session_invalid"));
                    response.getWriter().write(json);
                } catch (Exception writeErr) {
                    log.debug("write unauthorized response failed: path={}, err={}", request.getRequestURI(), writeErr.toString());
                }
                return false;
            }
            request.setAttribute(REQ_ATTR_USER_ID, userId);
            AuthContext.setUserId(userId);
            return true;
        } catch (Exception e) {
            // 统一返回 JSON，避免默认空响应/HTML。
            response.setStatus(401);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json;charset=UTF-8");
            try {
                String json = objectMapper.writeValueAsString(Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized"));
                response.getWriter().write(json);
            } catch (Exception writeErr) {
                log.debug("write unauthorized response failed: path={}, err={}", request.getRequestURI(), writeErr.toString());
            }
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContext.clear();
    }
}
