# 任务清单（轻量迭代）：WS query token 握手修复

方案包：202512272007_ws_query_token_handshake_fix  
范围：修复浏览器/前端使用 `?token=` 握手时无法完成握手导致 `auth_timeout` 的问题

## 任务列表

- [√] 后端：将 `WebSocketServerProtocolHandler` 改为 `checkStartsWith(true)`，允许 `/ws?token=...` 形式握手
- [√] 前端：发送好友申请/消息时，若收到 `ERROR`（按 `clientMsgId` 匹配）可提示失败原因，避免误以为“未落库”
- [√] 验证：`ws-smoke-test` 的 `HANDSHAKE(query token)` 从 FAIL(Timeout) 变为 OK

