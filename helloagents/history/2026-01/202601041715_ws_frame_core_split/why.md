# 变更提案: WS 核心能力拆分（Writer/Auth/Ping）

## 需求背景
当前 `WsFrameHandler` 已逐步瘦身，但仍承载了 AUTH/REAUTH、PING/PONG、以及通用 write/ack/error 的拼装与序列化逻辑，导致：
- 代码关注点混杂（协议解析/门禁/业务分发/回包细节耦合）
- 多个 handler 内重复实现 `write/writeError/writeAck`
- 容易出现“忘记切换到目标 channel eventLoop 执行写入”的隐性问题

## 变更内容
1. 新增 `WsWriter`：统一 WS JSON 写出、`ERROR` 回包、`ACK` 回包，并保证写入在目标 channel eventLoop 执行。
2. 新增 `WsAuthHandler`：承载 `AUTH/REAUTH`，并在 AUTH 成功后触发离线补发（同一连接仅一次）。
3. 新增 `WsPingHandler`：承载客户端 `PING->PONG` 与服务端 writer-idle 的 JSON `PING`（并刷新在线 TTL）。
4. `WsFrameHandler` 退化为“协议解析 + 鉴权门禁 + 路由分发”，不再承载上述业务细节。

## 影响范围
- **模块:**
  - `gateway`
- **文件:**
  - `src/main/java/com/miniim/gateway/ws/*`
  - `helloagents/wiki/modules/gateway.md`
  - `helloagents/CHANGELOG.md`
- **API:**
  - 无（协议 envelope 不变；`CALL_ERROR` 维持现状）
- **数据:**
  - 无

## 核心场景

### 需求: WS 写出统一
**模块:** gateway
统一 `write/writeAck/writeError` 并确保写入线程正确。

#### 场景: handler 在任意线程发消息
任意 handler（包括 DB 线程回调）调用写出接口：
- 预期结果：消息最终在目标 channel 的 eventLoop 上完成 writeAndFlush
- 预期结果：序列化失败/写失败可观测（日志 + future 失败）

### 需求: WS 认证拆分
**模块:** gateway
把 AUTH/REAUTH 从 frame handler 中抽离。

#### 场景: AUTH 成功触发补发一次
同一连接重复 AUTH：
- 预期结果：首次 AUTH 触发补发
- 预期结果：重复 AUTH 不重复补发

### 需求: WS 心跳拆分
**模块:** gateway
把 PING/PONG 从 frame handler 中抽离。

#### 场景: WRITER_IDLE 维持在线 TTL
连接长期无客户端心跳：
- 预期结果：服务端 writer-idle 发送 JSON PING，并刷新在线 TTL

## 风险评估
- **风险:** Writer 统一写出后，若重复 `eventLoop().execute` 可能造成不必要的任务排队
- **缓解:** Writer 内部做 `inEventLoop()` 判断；改造 handler 时移除多余的 `eventLoop().execute`

