# 技术设计: WS 发送者消息不乱序（单聊/群聊）

## 技术方案

### 核心技术
- Netty `Channel.attr` + `CompletableFuture`：为每个 `Channel` 维护一个“队尾 Future（tail）”，把每条业务消息 append 到 tail 后面形成 Future 链。
- `imDbExecutor`：承载 MyBatis/JDBC 等阻塞操作，避免阻塞 Netty `eventLoop`。
- `WsWriter`：统一保证写回在目标 channel 的 `eventLoop` 执行。

### 实现要点
1. 新增串行队列组件（建议放在 `com.miniim.gateway.ws`）：
   - `enqueue(channel, supplier)`：读取当前 tail，生成 next=tail.thenCompose(supplier)，并把 next 写回 tail。
   - 使用 `tail.handle((r,e)->null)` 作为桥接，确保上一条失败不会阻塞后续任务。
2. 改造 `WsSingleChatHandler` / `WsGroupChatHandler`：
   - `handle(ctx, msg)` 入口保持轻量校验（基本字段、fromUserId 读取）。
   - 将“落库 → ACK/ERROR → push”封装为一个 `CompletionStage<Void>` 任务，交给串行队列 `enqueue`。
   - 任务内部的阻塞 DB 操作继续使用 `imDbExecutor` 执行；ACK/ERROR 写回使用 `ctx.executor()` 或 `WsWriter` 保证回到 `eventLoop`。
   - 任务完成的定义：ACK/ERROR 已写回，且对外 push 调用已按顺序触发。
3. 错误处理策略：
   - 单条消息失败只影响该条：写回 `ERROR` 并释放队列，不影响后续消息继续处理。
4. 顺序保证边界：
   - 保证范围：同一发送者、同一设备绑定的同一条 WS `Channel` 上的发送顺序。
   - 非目标：跨连接（断线重连）、跨设备多端并发、以及客户端本地展示层的乱序修复。

## 架构决策 ADR

### ADR-001: 选择 per-channel Future 链保证发送顺序
**上下文:** 发送者连续发送两条消息时，由于 `imDbExecutor` 并发执行导致回调完成顺序反转，出现 ACK/下发乱序。
**决策:** 采用“每个 Channel 一条 Future 链”串行化 `SINGLE_CHAT/GROUP_CHAT` 的关键链路。
**理由:** 改动最小、与 Netty 模型一致（同一连接天然顺序语义）、无需引入全局分片线程池与跨实例路由改造。
**替代方案:** 业务线程池分片（按 userId/groupId） → 拒绝原因: 需要额外的分片执行器与背压治理；多实例下还需要请求路由到固定实例才能成立。
**影响:** 同一连接上的发送链路变为串行，可能增加单连接排队延迟，但换来严格的“发送者不乱序”保障。

## 安全与性能
- **安全:** 不引入敏感信息存储；保持原有鉴权/幂等逻辑不变；失败时确保释放队列避免阻塞造成 DoS。
- **性能:** 队列串行化只作用于“同一发送者同一连接”；DB 操作仍在 `imDbExecutor`，避免阻塞 `eventLoop`。

## 测试与部署
- **测试:**
  - 单元测试：串行队列保证“后入队任务不会早于前任务开始/完成回包”。
  - 手工测试：在本地通过人为增加第一条消息落库延迟，观察第二条不会先回 ACK/先下发。
- **部署:** 仅代码改动，无数据迁移；灰度关注 WS 延迟与队列积压（如需可追加队列长度上限与拒绝策略）。

