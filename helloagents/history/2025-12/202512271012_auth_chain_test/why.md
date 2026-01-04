# 为什么做这件事

你当前 IM 的鉴权链路同时覆盖：

- HTTP：`/auth/login`（注册/登录）→ 颁发 `accessToken + refreshToken`
- HTTP：`/auth/refresh`（刷新 accessToken）→ 依赖 Redis 存储 refreshToken 会话
- HTTP：`/auth/verify`（给网关或客户端验签/解析）→ 验证 accessToken 合法性
- WebSocket 握手（HTTP Upgrade）：`Authorization: Bearer <accessToken>` 或 `?token=<accessToken>` → 网关握手鉴权
- WebSocket 帧级：`AUTH` 帧（兼容旧客户端/触发离线补发）→ 绑定会话并触发离线补发逻辑

鉴权是 IM 全链路的“入口门槛”，一旦有回归会直接导致：

- WS 连接无法建立（401）
- 连接建立后无法发业务帧（unauthorized/token_expired）
- refreshToken 失效或 Redis 断连导致登录/刷新失败
- 离线补发触发时机错误（握手已鉴权但未触发 AUTH 逻辑，导致离线消息不补发）

因此需要一个“可复用、可观测、可解释”的全链路测试：

- 一键跑通 / 可选跑单项
- 在合适节点打印：关键变量、DB 状态、Redis 状态（脱敏）
- 输出中包含“为什么会这样返回”的解释，便于你面试/排查

