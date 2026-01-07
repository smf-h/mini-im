package com.miniim.auth.web;

import com.miniim.auth.dto.*;
import com.miniim.auth.service.AuthService;
import com.miniim.common.api.Result;
import com.miniim.common.ratelimit.RateLimit;
import com.miniim.common.ratelimit.RateLimitKey;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 鉴权相关 HTTP 接口。
 *
 * <p>这个项目里我们采用“双 Token”方案：</p>
 * <ul>
 *   <li>accessToken（JWT）：短有效期，用于每次请求/WS 首包鉴权。</li>
 *   <li>refreshToken：长有效期，用于换发新的 accessToken；本项目存 Redis（只存 hash）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @RateLimit(name = "auth_login", windowSeconds = 60, max = 5, key = RateLimitKey.IP_USER)
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        // 登录成功会返回：userId + accessToken + refreshToken + 两者的 ttl（秒）
        return Result.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public Result<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        // 用 refreshToken 换发新的 accessToken；refreshToken 本身仍然有效（并可选择滑动过期）
        return Result.ok(authService.refresh(request));
    }

    /**
     * 给 Netty 网关调用：验证 accessToken 是否有效，并解析 userId。
     */
    @PostMapping("/verify")
    public Result<VerifyResponse> verify(@Valid @RequestBody VerifyRequest request) {
        // 成功：Result.ok(data)
        // 失败：AuthService 会抛 JwtException/IllegalArgumentException，
        //      由 GlobalExceptionHandler 统一翻译为 Result.fail(...)（HTTP 状态码 401/400）。
        return Result.ok(authService.verify(request));
    }
}
