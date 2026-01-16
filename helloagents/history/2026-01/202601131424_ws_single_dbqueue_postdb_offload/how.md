# 技术设计: 单聊尾延迟治理（DB 队列 + Post-DB 隔离）

## 技术方案

### 核心技术
- Netty WebSocket（eventLoop 模型）
- Spring `ThreadPoolTaskExecutor`（线程池隔离）
- `CompletableFuture`（异步编排）

### 设计目标（验收对齐）
- **降低尾延迟**：重点压 `ws_perf single_chat.dbQueueMs` 与 `ws_perf single_chat.queueMs` 的 P95/P99
- **保持可用性**：允许一定 `ERROR reason=server_busy`，但比例控制在你确认的 <5%
- **不大改架构**：不引入 MQ/Streams；以“拆分/隔离/门禁/可观测”小步迭代

## 实现要点

### 1) 单聊：DB 回调线程只保留 ACK 快路径

#### 现状（需要优化的触发点）
`WsSingleChatHandler` 在 DB future 完成回调里仍会直接执行 `wsPushService.pushToUser(...)`。

问题：
- `WsPushService#pushToUser` 可能包含 `routeStore.get` / `clusterBus.publishPush` 等后置逻辑
- 这会把 **DB 线程池**当作“通用业务线程池”使用，导致：
  - DB 线程被 Redis/路由/序列化等拖住
  - `dbQueueMs` 上升 → 进一步放大 `queueMs`（单连接串行队列等待）→ E2E 长尾

#### 改造（推荐）
拆成两条路径：

1. **ACK(saved) 快路径（DB 回调线程执行）**
   - 只做：error/idem 清理、`wsWriter.writeAck(channel, ...)` 调度
   - 仍由 `WsWriter` 统一回切到 eventLoop 写出（保持线程安全与顺序）

2. **push/publish 慢路径（post-db executor 执行）**
   - `CompletableFuture.runAsync(() -> wsPushService.pushToUser(...), imPostDbExecutor)`
   - 拒绝/异常：按 best-effort 降级（记录计数/日志），不影响 ACK(saved) 完成

关键点：
- **done.complete 的完成点**仍以“ACK 已进入 writer 流程并记录 dbToEventLoop 延迟”为准，避免串行队列过早推进导致乱序放大
- push 不作为完成点依赖，避免慢 push 拖住单连接串行队列

### 2) WsWriter：非 eventLoop 调用也使用 encode executor

#### 现状（需要确认）
`WsWriter#write(Channel, ...)` 在“非 eventLoop 调用且开启 encode”时可能仍会在调用线程直接 `encode(env)`。

风险：
- 单聊 ACK(saved) 常在 DB 回调线程触发；若 ACK 带 body，JSON 编码会占用 DB 线程
- DB 线程“既做 DB 又做 encode”会加重 `dbQueueMs`

#### 改造（推荐）
当 `im.gateway.ws.encode.enabled=true` 且 `encodeOnExecutor=true` 时：
- 无论调用线程是否为 eventLoop，统一走 **encode executor → eventLoop write** 的两段式
- 继续使用现有 per-channel outbound 串行链保证顺序（避免 encode 异步造成“后发先至”）

### 3) 可控过载：有界队列 + server_busy

目的：把秒级排队变成可控拒绝，收敛 P95/P99。

建议策略：
- `imDbExecutor` / `imPostDbExecutor` / `imWsEncodeExecutor` 都采用有界队列
- 队列满触发 `AbortPolicy`（或显式拒绝）：
  - 单聊入站：返回 `ERROR reason=server_busy`
  - push（best-effort）：记录 drop/reject 计数即可

配置建议（起点，需用压测回归校准）：
- `im.executors.db.queue-capacity`: 1k~5k（避免 10k 级无界排队）
- `im.executors.post-db.queue-capacity`: 1k~5k
- `im.executors.ws-encode.queue-capacity`: 1k~5k

> 说明：队列越大，吞吐看起来越“稳”，但尾延迟更容易炸；你当前目标是压尾延迟，因此建议“宁可拒绝也不堆积”。

## 测试与部署

### 回归脚本（Windows / PowerShell）
- 5 实例综合回归：`scripts/ws-cluster-5x-test/run.ps1`
- 慢消费者/背压：`scripts/ws-backpressure-multi-test/run.ps1`

### 观察与判定（建议）
1. `ws_perf single_chat` 分段：
   - `dbQueueMs` P50/P95/P99 是否下降（重点）
   - `queueMs` P95/P99 是否随之下降（重点）
   - `dbToEventLoopMs` 继续保持低位（防回归）
2. `single_e2e_5000.json`：
   - P95/P99 是否下降（以对照回归为准）
3. 错误比例：
   - `ERROR reason=server_busy` + `wsError` 总体 <5%

## ADR（可选）

### ADR-001: 过载策略选择（尾延迟优先）
**上下文:** 单聊长尾主要由排队导致；无界排队会放大延迟与内存风险。  
**决策:** 用有界队列+拒绝策略将排队转化为 `server_busy`，以收敛 P95/P99。  
**替代方案:** 无界队列“尽量不拒绝” → 拒绝原因: 尾延迟不可控且放大慢消费者风险。  
**影响:** 在峰值会出现一定比例失败（你确认可接受 <5%），但整体稳定性更强。  
