# 怎么做

## 测试范围

### 1) WebSocket 鉴权链路

- 握手鉴权：
  - Header：`Authorization: Bearer <accessToken>`
  - Query：`?token=<accessToken>` / `?accessToken=<accessToken>`
- 握手后帧鉴权：
  - 发送 `AUTH` 帧后收到 `AUTH_OK`
  - token 过期后的业务帧应返回 `token_expired` 并关闭（若可复现）

### 2) HTTP 鉴权链路（需要 Redis）

- `POST /auth/login`：返回 `userId/accessToken/refreshToken`
- `POST /auth/verify`：验证 accessToken 可解析 userId
- `POST /auth/refresh`：刷新 accessToken（依赖 Redis 的 refreshToken 会话）

若 Redis 未启动：HTTP 刷新链路标记为“跳过”，并在输出中给出原因（不让测试整体失败）。

## 可观测性与脱敏

- 输出每步 WS 消息的 `raw` JSON，但对 token 做脱敏（只保留前后少量字符）。
- DB 打印：
  - 每个场景按 `serverMsgId` 查询 `t_message.status/from/to/content/updated_at`
  - 必要时打印 `t_message` 状态聚合（计数）
- Redis 打印（可选，需 redis-cli 或可连接 Redis）：
  - `im:gw:route:<userId>`（路由键值与 TTL）
  - refreshToken 会话相关 key（只打印 hash，不打印明文）
- 本地缓存/关键变量：
  - 用“幂等行为证明”（同一 clientMsgId 返回同一 serverMsgId）作为服务端幂等缓存生效的观测
  - 输出关键参数：scenario、timeoutMs、ackTimeoutMs（从配置或参数获取）

## 交付物

- 扩展 `scripts/ws-smoke-test`：新增 `auth` 场景（以及必要的打印/脱敏能力）
- 更新 `helloagents/wiki/testing.md`：补齐鉴权全链路测试说明与输出字段解释
- 更新 `task.md`：记录实际变动（Task 变动记录），完成后迁移到 `helloagents/history/YYYY-MM/`

