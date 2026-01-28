# WS 鉴权链路收敛：改为 AUTH-first（连接后首包鉴权）

## 背景 / 现状

当前 WS 鉴权存在“双入口 + 重复副作用”的结构：

- 握手阶段（HTTP Upgrade）会解析 token、校验并 `bind(userId)`，并执行路由写入与踢线：
  - `WsHandshakeAuthHandler`：`extractAccessToken()` → `sessionRegistry.bind()` → `afterAuthed()`。
- 连接建立后，前端仍会发送 `AUTH {token}`：
  - `WsAuthHandler.handleAuth()` 在“已 authed”时仍会回 `AUTH_OK`，并再次 `afterAuthed()`（刷新路由 TTL、关闭旧连接等）。

这会带来几个问题：

- **副作用重复**：路由写入/踢线/补发门禁分散在握手与 AUTH 两处，未来引入 deviceId 维度会显著放大复杂度与边界。
- **token 泄露面**：前端当前把 token 放在 WS URL query（更容易被代理/日志记录）；虽然本项目已有日志脱敏，但 URL 层面仍不理想。
- **不利于演进**：想做“auth timeout”“未鉴权消息白名单”等常见门禁时，需要同时考虑两个鉴权入口。

## 目标

采用主流的 **AUTH-first（应用层首包鉴权）**：

1) WS 握手只负责升级协议，不绑定 userId
2) 连接建立后，客户端必须在短时间内发送 `AUTH {token}` 完成鉴权
3) 鉴权成功后，才允许业务消息；鉴权失败/超时则断连
4) 路由写入/踢线/离线补发触发等“副作用”只在 `AUTH` 成功的唯一入口执行

## 非目标

- 不在本次改造中引入多端并存（deviceId 路由、多设备游标等）。
- 不改变现有 ACK/补发/群聊策略等业务语义（仅收敛鉴权入口与门禁）。
- 不要求向后兼容“只带 query token 不发 AUTH”的旧客户端（如需兼容，另起任务做灰度策略）。

## 方案选择（采用 AUTH-first）

### 1) 服务端行为口径

- 握手阶段：
  - 只校验 `path` 合法性（保留 `wsPath` 过滤），不解析 token、不绑定 userId、不写路由、不踢线。
- 连接建立后：
  - 增加 `auth timeout=3s`：超时未鉴权，先回 `ERROR reason=auth_timeout` 再断连。
  - `WsFrameHandler` 统一门禁：未鉴权状态仅允许 `AUTH/PING/PONG`；其它消息（包含 `REAUTH`）先回 `ERROR reason=unauthorized`（沿用现有 reason）再断连。
- `AUTH` 成功：
  - 唯一入口执行 `sessionRegistry.bind()`、`routeStore.setAndGetOld()`、`closeOtherChannels()`、跨实例 `KICK`、以及（可开关的）`AUTH` 后离线补发。
- `AUTH` 在已鉴权连接上重复发送：
  - 仅回 `AUTH_OK`（无副作用：不刷新路由、不踢线、不触发补发），避免重复执行。
- `AUTH_FAIL`：
  - 保持当前语义：服务端发出 `AUTH_FAIL` 后立即断连（无论 reason），由客户端走统一重连/登录态处理。

### 2) 前端行为口径

- WS URL 不再携带 `token` query：
  - 建连成功后立即 `send({type:'AUTH', token})`，等待 `AUTH_OK` 作为“可用”信号。
- `AUTH_FAIL` / `ERROR` 的处理保持现有逻辑（以 `api.md` 口径为准）。

## 代码改造点（以现有实现为准）

需要收敛的关键位置：

- 握手阶段鉴权：`src/main/java/com/miniim/gateway/ws/WsHandshakeAuthHandler.java`
- AUTH 入口：`src/main/java/com/miniim/gateway/ws/WsAuthHandler.java`
- 协议分发与门禁：`src/main/java/com/miniim/gateway/ws/WsFrameHandler.java`
- 前端 WS 连接：`frontend/src/stores/ws.ts`

建议新增/调整：

- 新增 `WsAuthTimeoutHandler`（Netty pipeline handler）：`channelActive` 时定时；鉴权成功后取消；超时断连。
- `WsFrameHandler` 增加统一“未鉴权白名单”拦截（减少各业务 handler 的重复 `unauthorized` 分支）。

## 风险与收益

收益：

- 鉴权与副作用收敛，后续引入 `deviceId`、灰度策略、或更复杂的踢线规则时更可控。
- token 从 URL 移除，更符合主流安全与隐私习惯。
- auth timeout + 统一门禁更容易实现，降低“空连/刷包/协议探测”的成本。

风险：

- 破坏性变更：不再支持“仅握手带 query token 不发 AUTH”的客户端（本方案明确不做兼容）。
- 需要对前端重连逻辑进行一次验证（AUTH 超时/失败时的 close 与跳转）。

## 验收标准

- 连接建立后未发 `AUTH`：在超时窗口后被断连（可带 `auth_timeout`）。
- 先发业务消息再发 `AUTH`：立即 `unauthorized` 并断连（避免灰色状态）。
- 正常流程：open → AUTH → AUTH_OK → 业务消息可用。
- 重复 `AUTH`：只回 `AUTH_OK`，不会重复写路由/踢线/补发（看日志与行为）。
