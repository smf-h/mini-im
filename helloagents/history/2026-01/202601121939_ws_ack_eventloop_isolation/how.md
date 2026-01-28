# 怎么做：把 ACK/下发的“回切闭包”从 eventLoop 拆出去

## 设计原则

- **eventLoop 只做 I/O + 极轻逻辑**：解码/编码、writeAndFlush、必要的少量状态检查。
- **业务后置逻辑在线程池做**：DB 完成后的构造回包、触发 push/dispatch、打点。
- **写回仍由 Netty eventLoop 执行**：通过 `WsWriter` 统一 marshal，避免跨线程直接 writeAndFlush 导致的隐患。
- **指标口径不作弊**：继续能度量“DB 完成 → eventLoop 开始执行写回任务”的排队（用于验证优化是否真实生效）。

## 关键改动点（最小代码改动）

### 1) `WsWriter` 增强：支持 Channel 级写 ACK + 记录 eventLoop 排队延迟

新增能力：

- `writeAck(Channel ch, ...)`：允许在非 eventLoop 线程直接调用，不依赖 `ChannelHandlerContext`
- `write(Channel ch, WsEnvelope env, LongConsumer onEventLoopDelayNs)`：
  - 若当前线程不在 eventLoop：在 `ch.eventLoop().execute(...)` 里记录“排队延迟”，再执行 `doWrite`
  - 若 fail-fast（不可写）：也要触发回调（避免上层等待导致串行队列卡死）

用途：

- 把 handler 中的 `dbToEventLoopMs` 从“ctx.executor().execute 的排队”迁移为“写回任务在 eventLoop 的排队”，口径更贴近真实瓶颈。

### 2) 单聊：`WsSingleChatHandler` 改为“DB 回调线程直接后置处理 + 由 WsWriter 回切写回”

改造前（问题模式）：

- `saveFuture.whenComplete(...)` 中用 `ctx.executor().execute(大闭包)`：
  - 在 eventLoop 做 ACK 回包、push、日志
  - 导致 `dbToEventLoopMs` 上升、eventLoop backlog

改造后（目标模式）：

- `saveFuture.whenComplete(...)` 直接在回调线程：
  1. 处理 error/idem 清理
  2. 触发 `wsWriter.writeAck(channel, ...)`（由 writer 自己回切 eventLoop）
  3. 触发 `wsPushService.pushToUser(...)`（可在回调线程执行）
  4. 等待 writer 记录到“eventLoop 排队延迟”后再 `done.complete` + `maybeLogPerf`

### 3) 群聊：先不改为“回调线程直接 dispatch”（避免写出洪峰反噬 eventLoop）

压测验证中发现：群聊下发属于“放大器”链路（一次发送 → 多用户 fanout），如果在 DB 回调线程并发触发 dispatch，容易在短时间内向多个 channel 的 eventLoop **突发提交大量写任务**，进而导致：

- eventLoop backlog 上升（串行队列 `queueMs` 放大）
- `server_busy`/ACK 迟到比例上升
- 群聊 E2E 分位数明显恶化

因此本轮改造聚焦在**单聊 ACK 回切减负**；群聊仍保持原有“回切到 eventLoop 后再 dispatch”的节奏（天然限速），后续若要继续优化群聊，需要额外引入：

- **dispatch 并发度/速率门控**（例如按实例限并发、按群限流、按 eventLoop 分批提交）
- **fanout 隔离**（避免一个群的写洪峰影响所有连接）

## 有序性影响评估

当前系统的“可验证有序范围”（用户已确认默认）：

- 单聊：同一发送者（同一连接）强有序（依赖 `WsChannelSerialQueue`）
- 群聊：同一发送者有序（对“跨发送者全局有序”不做强保证）

本改造不改变串行队列的入站串行化策略；`done.complete` 的完成点仍绑定在“写回任务已进入 eventLoop（并记录排队延迟）之后”，避免串行队列过早推进导致的乱序放大。

## 失败与降级

- 若 `channel` 不可写且开启 fail-fast：写回会失败（返回 failed future），上层仍需及时 `done.complete`，避免串行队列卡死。
- `server_busy`（入站 pending 超限）仍按现有门禁返回 ERROR；这是用“可控失败”换“整体尾延迟不爆炸”的取舍。

## 验证方法（与指标绑定）

1. 5 实例基线对照：`scripts/ws-cluster-5x-test/run.ps1`（关注 `clients=5000` 的 `single_e2e` 与 `ws_perf_summary_*`）
2. 关键指标：
   - `ws_perf single_chat.dbToEventLoopMs` P95/P99 是否下降
   - E2E P50/P95/P99 是否下降
   - `wsError` 中 `server_busy` / backpressure 是否上升（以及是否在可接受范围）
3. 慢消费者回归：`scripts/ws-backpressure-multi-test/run.ps1`（确保背压闭环仍生效）
