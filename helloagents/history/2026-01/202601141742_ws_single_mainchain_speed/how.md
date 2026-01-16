# 技术设计: 单聊主链路提速（ACK/投递更快，P50 回到 <1s 量级）

## 技术方案

### 核心技术
- **后端:** Spring Boot + Netty WebSocket
- **异步:** `imDbExecutor`（主 DB 写）+ `imPostDbExecutor`（低优先级/后置 DB 写）
- **观测:** `ws_perf single_chat`（queueMs/dbQueueMs/saveMsgMs/updateChatMs/dbToEventLoopMs）

### 实现要点

#### 1) `t_single_chat.updated_at` 异步化（从主链路拆出）

**现状：**
- `WsSingleChatHandler` 在 DB 任务内执行 `messageService.save(...)` 后立刻执行 `singleChatService.update(...updatedAt...)`，ACK(saved)/push 必须等待该 UPDATE 完成。

**改造：**
- 主 DB 任务只做：幂等 claim → getChatId → ensureMembers → save message。
- DB 任务完成后立刻：
  - 发送 ACK(saved) 给 sender
  - pushToUser 给 receiver（在线投递）
- `updated_at` 更新改为异步 best-effort：
  - 仍使用 `SingleChatUpdatedAtDebouncer` 判断窗口是否需要更新
  - 满足则把 UPDATE 提交到 `imPostDbExecutor` 执行
  - `RejectedExecutionException` 或 DB 异常：记录事件但不阻塞主链路

**观测口径变化：**
- `ws_perf single_chat.updateChatMs` 将不再代表主链路耗时（预计多数为 0），需在回归报告中说明。

#### 2) 串行队列调度优化（减少 eventLoop pending tasks）

**现状：**
- `WsChannelSerialQueue.invokeOnEventLoop` 无论是否已在 eventLoop 都使用 `eventLoop().execute(...)` 调度，导致每条消息额外制造一个 eventLoop task。

**改造：**
- 在 `invokeOnEventLoop` 中增加：
  - 若 `channel.eventLoop().inEventLoop()`：直接执行 `safeGet(taskSupplier)` 并回填 future
  - 否则：维持 `eventLoop().execute(...)`

**预期收益：**
- 降低 eventLoop backlog，间接降低 `dbToEventLoopMs` 与整体 queueing delay。

#### 3) 压测 pinning 修正（5 实例均衡 + 保持跨实例）

**现状：**
- `WsLoadTest` 的 `rolePinned` 将 sender/receiver 固定为 `wsUrls[0/1]`，5 实例时会产生热点。

**改造：**
- 当 `rolePinned=true` 且 `wsUrls.size()>1`：
  - `wsUrlIndex = floorMod(userId, wsUrls.size())`
  - sender/receiver 都按自身 `userId` 选择 wsUrl（由于 peer=userId±1，天然跨实例）

## 安全与性能
- **安全:** 不新增权限，不改变鉴权与敏感数据处理；过载下仍以现有 `server_busy`/背压策略保护网关。
- **性能:** 主目标是降低 `dbQueueMs/queueMs`；通过减少主链路 DB 写放大与 eventLoop 调度开销实现。

## 测试与部署

### 回归测试（必须）

1) **单聊 open-loop 常规负载**
- 5 实例，`clients=5000`，`msgIntervalMs=3000`，运行 60s，重复 3 次取平均
- 关注：E2E（p50/p95/p99）、`wsErrorAvg`、`ws_perf single_chat` 分段（queueMs/dbQueueMs/dbToEventLoopMs）

2) **单聊 burst 验证（确认不回退）**
- 5 实例，`clients=5000`，`msgIntervalMs=100`
- 关注：是否出现队列爆炸/延迟雪崩；`updated_at` 异步不会把主链路拖慢

3) **一致性抽查**
- 在 Web/HTTP 会话列表接口上抽查：消息发送后 ≤1s 内置顶（允许在过载时延后但不影响 ACK/投递）

