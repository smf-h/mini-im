# 为什么要做：ACK 排队 / eventLoop 过载（隔离不足）

## 背景与现象

在 5 实例压测（Windows 单机）中，单聊 E2E 端到端延迟出现“秒级~十几秒级”的长尾。结合分段打点日志（`ws_perf single_chat`），延迟主要不是 DB 执行耗时本身，而是**排队**：

- `queueMs`：同连接串行队列排队（入站串行化带来的排队）
- `dbQueueMs`：`imDbExecutor` 线程池排队（DB 任务提交后等待线程）
- `dbToEventLoopMs`：DB Future 完成后“回到 Netty eventLoop 写回 ACK / 广播”的排队

其中，调参把 `dbQueueMs` 从秒级压到接近 0 后，`dbToEventLoopMs` 反而上升，说明瓶颈**从 DB 线程池排队迁移到了 eventLoop 的任务队列/执行时间**。

## 根因假设（需要用代码路径验证）

当前消息处理链路中存在“后置逻辑回到 eventLoop 执行”的模式：

- DB Future 完成后使用 `ctx.executor().execute(...)` 把后续处理（ACK/推送/群聊下发等）切回 eventLoop。
- 在高并发写入/群聊广播/补发等场景下，这会让 eventLoop 同时承担：
  - ACK 写回与序列化
  - 在线推送（push）触发
  - 群聊下发（dispatch）与大量循环/路由分组
  - 额外的 eventLoop pending tasks（“回切闭包”本身也是任务）

当 eventLoop 被这些后置逻辑挤占时，即使 DB 很快完成，ACK 也会在 eventLoop 队列里排队，形成“ACK 排队 → E2E 长尾”的放大效应。

## 目标（最小改动、最高 ROI）

在不大改架构的前提下，先把“背压门禁/隔离”补到最关键、最容易见效的位置：

1. **把后置逻辑从 eventLoop 移走**：DB 回调线程直接做后置逻辑（构造回包、触发 push/dispatch、打点），写回通过 `WsWriter` 自行 marshal 到 eventLoop。
2. **把背压门禁提前到调度前**（已完成）：`WsWriter.write*` 在调度前对 `!ch.isWritable()` 做 fail-fast，避免在慢端/高压下堆积 eventLoop pending tasks。
3. **保留可观测性**：继续能量化 `dbToEventLoopMs`（不通过“改指标口径”取巧），用于验证改造是否真的降低 eventLoop 排队。

## 成功标准（本方案的验收口径）

以 5 实例、`clients=5000` 单聊 E2E 为主回归场景：

- `ws_perf single_chat.dbToEventLoopMs`：P95/P99 明显下降（反映 eventLoop 回切排队收敛）
- E2E：P50 明显下降；P99 明显下降或至少稳定（不再随压测时间线性变差）
- 吞吐：sentPerSec 不下降（允许以 `server_busy` 换尾延迟收敛，但需要量化失败率）

## 风险与取舍（提前声明）

- 将后置逻辑移出 eventLoop 后，必须确保：
  - 写回仍在对应 channel 的 eventLoop 执行（由 `WsWriter` 保证）
  - 串行队列的“完成点”仍然正确（避免同连接处理乱序扩大）
- 该改造不会解决所有 E2E 长尾：若 DB 实际执行或外部依赖（Redis/路由）变慢，仍会出现延迟；本次目标是**减少不必要的 eventLoop 排队与线程切换开销**。

