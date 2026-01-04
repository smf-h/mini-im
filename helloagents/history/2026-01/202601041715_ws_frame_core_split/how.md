# 技术设计: WS 核心能力拆分（Writer/Auth/Ping）

## 技术方案
### 核心技术
- Java 17 + Spring Boot 3
- Netty WebSocket（TextWebSocketFrame）

### 实现要点
- `WsWriter`
  - 提供 `write(ctx, env)` / `write(ch, env)` 两套入口
  - 内部保证写入发生在目标 channel 的 eventLoop（`inEventLoop()` 否则 `eventLoop().execute(...)`）
  - 提供通用 `writeError`/`writeAck`（保持现有 `ERROR/ACK` envelope 结构不变）
- `WsAuthHandler`
  - `AUTH`：解析 token，`sessionRegistry.bind(channel, uid, expMs)`，并触发补发（同一连接仅一次）
  - `REAUTH`：校验 uid 一致后刷新 expMs，不触发补发
  - “补发一次”标记放在 channel attribute：`im_resend_after_auth_done`
- `WsPingHandler`
  - 客户端 `PING`：鉴权门禁 + 过期校验 + `touch` + 回 `PONG`
  - 服务端 `WRITER_IDLE`：可选 `touch` + 下发 JSON `PING`
- `WsFrameHandler`
  - 保留：JSON 解析、鉴权门禁（AUTH/REAUTH 例外）、switch 分发、空闲事件处理、channelInactive
  - 移除：AUTH/REAUTH/PING 具体实现与通用写出实现

## 安全与性能
- **安全:** AUTH/REAUTH 严格校验 token 与 uid 一致性；未鉴权/过期连接直接关闭
- **性能:** 统一写出减少重复序列化代码；writer 内部减少不必要的 `eventLoop().execute`

## 测试与部署
- **测试:** `mvn test`、`npm -C frontend run build`
- **部署:** 无额外步骤（仅代码重构）

