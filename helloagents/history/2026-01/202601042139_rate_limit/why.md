# 变更提案: Redis 限流（@RateLimit + AOP）与 WS 限频建议

## 需求背景
IM 系统存在若干“成本高/风险大”的入口，如果没有频控，很容易被刷爆或被恶意攻击：
- 登录接口：暴力破解、撞库
- 发起好友申请：恶意骚扰、垃圾请求
- 创建群/加群等写操作：写库 + 初始化开销

同时需要注意：WebSocket 收包不走 Spring MVC Controller，不能直接用 AOP 注解限流，需要在 Netty handler 层做限频。

## 变更内容
1. 新增 `@RateLimit` 注解，支持按 `IP / USER / IP+USER` 维度做限流。
2. 新增 AOP 切面：基于 Redis Lua 原子计数（`INCR + EXPIRE`）实现固定窗口限流。
3. 新增统一错误响应：超过阈值返回 HTTP 429 + `Result.fail(ApiCodes.TOO_MANY_REQUESTS, ...)`（与现有 Result 风格一致）。
4. 在关键接口上启用（按“推荐名单”）：`/auth/login`、好友申请创建、创建群/加群等写操作。
5. 提供 WS 限频建议与可选实现点（本次先出方案，不强制落地）。

## 影响范围
- **模块:** auth/web, domain/controller, common/web, common(新增 ratelimit), helloagents(方案包/文档)
- **Redis:** 新增计数 key（按接口维度），TTL 与窗口一致

## 核心场景

### 需求: 登录防爆破
**模块:** auth

#### 场景: 同一 IP 对多个用户名爆破
- 以 `IP+username` 作为 key
- 60 秒窗口内超过阈值直接 429

### 需求: 好友申请防刷
**模块:** domain/friend

#### 场景: 同一用户高频发送好友申请
- 以 `userId` 作为 key
- 超限 429，避免骚扰与写库压力

### 需求: 创建群/加群等写操作防刷
**模块:** domain/group

#### 场景: 脚本高频创建群
- 以 `userId` 作为 key
- 超限 429，避免资源被刷爆

## 风险评估
- **风险:** Redis 抖动/不可用导致限流失效或影响接口
  - **缓解:** 提供可配置的 fail-open/fail-closed；默认建议 fail-open 保可用（结合网关/Nginx 做基础限流）
- **风险:** 反向代理下 IP 获取不准导致误伤
  - **缓解:** 支持 `trustForwardedHeaders` 配置；仅在确认接入可信反代时启用

