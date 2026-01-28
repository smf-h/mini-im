# 技术设计: Redis 限流（@RateLimit + AOP）与 WS 限频建议

## 技术方案

### 核心技术
- Spring AOP（拦截 Controller 方法）
- Redis Lua（原子计数+设置 TTL）
- 统一错误返回（GlobalExceptionHandler 扩展 429）

### 注解与 key 维度
建议注解形态：
- `@RateLimit(name="auth_login", windowSeconds=60, max=5, key=IP_USER)`
- key 组成：`im:rl:{name}:{dimension}:{value}`

维度定义：
- `IP`：客户端 IP（支持 `X-Forwarded-For` / `X-Real-IP`）
- `USER`：已登录用户的 `userId`（从 `AuthContext`）
- `IP_USER`：`ip + username`（用于登录等未登录场景，username 从参数解析）

### Redis 脚本
Lua（固定窗口）：
1. `INCR key` 得到计数
2. 若计数为 1，则 `EXPIRE key windowSeconds`
3. 若计数 > max，则拒绝（可返回 TTL 用于 retry-after）

### 接入点（按“推荐名单”）
1. `POST /auth/login`：`IP_USER`（ip+username），例如 `60s/5次`
2. 好友申请创建（HTTP）：如 `POST /friend/request/by-code`：`USER`，例如 `60s/3次`
3. 创建群/加群等写接口：`USER`，例如 `60s/1次`

> 阈值不做强绑定：通过注解参数与配置文件可调；本次按“默认值”落地即可。

### WS 限频（建议）
WS 不建议用 Redis 限流（连接天然落在单实例），建议在 Netty handler 内：
- `channel` 维度：每秒最多 N 条（超出直接 close）
- `userId` 维度：同 user 多连接时可选汇总（若未来支持多端）

### 反向代理 IP
在确认部署在反代后时启用：
- `im.ratelimit.trust-forwarded-headers=true`
- 取 `X-Forwarded-For` 的第一个公网 IP（或直接取第一个值）

### 统一返回
新增异常 `RateLimitExceededException`：
- GlobalExceptionHandler 映射到 HTTP 429
- body：`Result.fail(ApiCodes.TOO_MANY_REQUESTS, "too_many_requests")`（可附带 retryAfterSeconds）

## 测试与部署
- 单元测试：Lua 执行结果解析、key 生成、IP 解析（含 forwarded headers）
- 集成测试（可选）：Testcontainers Redis 验证并发下计数与 TTL 行为

