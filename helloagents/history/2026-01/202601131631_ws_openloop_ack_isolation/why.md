# 变更提案: 固定发送速率压测（open-loop）+ ACK 推进隔离（1s 最终一致）

## 需求背景

当前我们在单聊链路上看到的主要矛盾是：**优化 ACK/回切路径后，压测端因为 inflight/ACK 门控变快而“发得更猛”**，导致系统被推到更高负载，出现“优化了但数据更差”的错觉；同时，服务端 `ACK(delivered/read/ack_receive)` 的 DB 推进逻辑与 `SINGLE_CHAT` 落库共用同一 DB 线程池（以及同一 DB 连接池），会放大 `dbQueueMs`，进一步推高 `queueMs` 与 E2E 尾延迟。

你已确认：
- `delivered/read/ack_receive` 允许 **1s 最终一致**
- 压测希望改成“发送频率不因 ACK 变化”（open-loop），用于公平对照
- `push/publish` 不应放入 post-db（它属于 E2E 关键路径）

## 变更内容

1. **压测：新增 open-loop 发送模式（固定速率）**
   - 在 `scripts/ws-load-test/WsLoadTest.java` 增加 open-loop：发送端按固定节奏“尝试发送”，不以 inflight/ACK 作为节流依据
   - 输出同时包含：attempted（尝试次数）/sent（实际发出）/skipped（本地安全门禁跳过）/wsError，用于对照“offered load 是否一致”

2. **服务端：ACK 推进 DB 工作隔离（1s 最终一致）**
   - 把 `WsAckHandler` 的 DB 推进（markDelivered/markRead）从主 `imDbExecutor` 中隔离到独立 executor（例如 `imAckExecutor`）
   - 在允许 1s 延迟前提下，引入合并/去抖（同一用户/同一会话内只保留最大 msgId），减少 DB 写放大
   - 回执推送给发送方（read/delivered）允许同样 1s 级延迟（与 DB 推进同节奏）

3. **可观测与验收口径**
   - open-loop 下对照：固定 attempted/sent 的条件下，比较 `ws_perf single_chat.dbQueueMs/queueMs/totalMs` 与 E2E 分位数
   - 增补 `ws_perf ack`（或扩展现有日志）以量化：ackQueueMs/ackDbMs/ackPushMs

## 影响范围

- **模块:** scripts（压测）、gateway/ws（ACK）、config（executor）
- **文件（预估）:**
  - `scripts/ws-load-test/WsLoadTest.java`
  - `scripts/ws-load-test/run.ps1`
  - `scripts/ws-cluster-5x-test/run.ps1`
  - `src/main/java/com/miniim/gateway/ws/WsAckHandler.java`
  - `src/main/java/com/miniim/config/ImExecutorsConfig.java`
  - `src/main/java/com/miniim/config/ImAckExecutorProperties.java`（新增）
  - `helloagents/wiki/testing.md`、`helloagents/wiki/modules/gateway.md`、`helloagents/CHANGELOG.md`

## 核心场景

### 需求: 公平压测对照（open-loop）
**模块:** scripts

#### 场景: 单聊 open-loop（5000 clients）
- 发送端固定速率（不因 ACK 变化）
- 以 attempted/sent 接近为对照前提，比较 E2E P95/P99 与 `ws_perf single_chat.*`

### 需求: ACK 推进隔离（1s 最终一致）
**模块:** gateway/ws

#### 场景: 高 ACK 压力（delivered/read）
- `WsAckHandler` 高并发推进游标时，不得抬高 `SINGLE_CHAT` 的 `dbQueueMs`
- 允许 1s 延迟，但要求系统稳定、尾延迟收敛

## 风险评估

- **风险:** open-loop 若无限制，压测端可能产生本地堆积（内存上涨）
  - **缓解:** 增加本地安全门禁（maxInflightHard/发送失败计数），并把 skipped 明确输出，不隐藏负载变化
- **风险:** ACK 1s 合并会让“已读/送达”显示有 1s 级滞后
  - **缓解:** UI 侧以游标推进呈现，本就符合最终一致；并记录合并窗口用于解释

