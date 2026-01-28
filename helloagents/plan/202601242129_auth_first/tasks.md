# 任务清单：WS 鉴权收敛为 AUTH-first

> 说明：目标是“连接后首包 AUTH”成为唯一鉴权入口；握手阶段不再绑定用户与触发副作用。

## A. 服务端：握手层降级为“只升级、不鉴权”

- [ ] `WsHandshakeAuthHandler`：保留 wsPath 过滤，但移除 token 解析与 `sessionRegistry.bind/afterAuthed`
- [ ] 明确握手失败策略：仅 path 不匹配/协议错误才拒绝；鉴权不在握手阶段发生

## B. 服务端：auth timeout（防空连占资源）

- [ ] 新增 `WsAuthTimeoutHandler`
  - [ ] `channelActive` 后启动定时（默认 3~5s）
  - [ ] `channelActive` 后启动定时（默认 3s）
  - [ ] 若超时仍未 `sessionRegistry.isAuthed`：先回 `ERROR reason=auth_timeout` 再 `close`
  - [ ] 鉴权成功后取消定时任务
- [ ] 增加配置项（可选）：`im.gateway.ws.auth.auth-timeout-ms`

## C. 服务端：未鉴权门禁统一收口

- [ ] `WsFrameHandler`：未鉴权状态仅允许 `AUTH/PING/PONG`（`REAUTH` 视为未授权操作）
- [ ] 其他 type：先回 `ERROR reason=unauthorized`（沿用现有 reason）再断连（减少各 handler 重复校验分支）

## D. 服务端：AUTH 成为唯一入口（副作用只发生一次）

- [ ] `WsAuthHandler.handleAuth`
  - [ ] 首次鉴权成功：`bind + routeStore.setAndGetOld + closeOtherChannels + clusterKick + (可选) after-auth resend`
  - [ ] 已鉴权连接重复 `AUTH`：仅回 `AUTH_OK`（不再执行 `afterAuthed()`，不触发补发）
  - [ ] 鉴权失败：发出 `AUTH_FAIL` 后立即断连（保持现状口径，不做 reason 分流）

## E. 前端：移除 URL query token，保留首包 AUTH

- [ ] `frontend/src/stores/ws.ts`：WS URL 不再拼接 `?token=...`
- [ ] `onopen` 后立即发送 `AUTH {token}`，等待 `AUTH_OK`
- [ ] 验证 `AUTH_FAIL/ERROR(kicked/session_invalid/token_expired)` 的现有处理逻辑不回归

## F. 文档与验收

- [ ] 文档口径更新
  - [ ] `helloagents/wiki/api.md`：明确“握手不鉴权，AUTH-first”
  - [ ] `helloagents/wiki/ws_delivery_ssot_onepager.md`：补充 auth timeout / 未鉴权白名单 / token 不上 URL
- [ ] 联调验收用例（最小集）
  - [ ] 不发 AUTH：超时断连
  - [ ] 先发业务消息：unauthorized + 断连
  - [ ] 正常 AUTH：业务可用
  - [ ] 重复 AUTH：无重复踢线/无重复补发
