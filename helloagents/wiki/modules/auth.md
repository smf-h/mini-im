# 模块: auth

## 职责
- 提供登录、token 刷新与 token 校验
- 管理 JWT 与 refresh token 存储

## 关键实现（以代码为准）
- 控制器：`com.miniim.auth.web.AuthController`（/auth 下的 login/refresh/verify）
- 拦截器与上下文：`AccessTokenInterceptor`、`AuthContext`、`WebMvcAuthConfig`
- 服务：`AuthService`、`JwtService`、`RefreshTokenStore`、`RedisRefreshTokenStore`、`TokenHasher`
- DTO：`LoginRequest/Response`、`RefreshRequest/Response`、`VerifyRequest/Response`