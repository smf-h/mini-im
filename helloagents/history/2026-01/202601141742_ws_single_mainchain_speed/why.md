# 变更提案: 单聊主链路提速（ACK/投递更快，P50 回到 <1s 量级）

## 需求背景

在 5 实例 + open-loop 压测（`clients=5000`、`msgIntervalMs=3000`）下，单聊 E2E 的 **p50 达到秒级**，对“单聊即时性”不符合预期。

基于现有 `ws_perf single_chat` 分段结果，当前主耗时主要来自“排队”而非“单条 SQL 很慢”：
- `queueMs`（连接级串行队列排队）与 `dbQueueMs`（DB executor 排队）是主要贡献项。

另外，当前压测脚本的 `rolePinned` 行为会把 sender/receiver 固定到 `wsUrls[0/1]`，在 5 实例场景下会导致**负载只落在 2 个实例**，形成热点，放大队列排队，从而把 E2E 拉到秒级。

业务取舍（已确认）：
- 保持 `ACK(saved)` 语义：消息已落库（`t_message`）。
- `t_single_chat.updated_at` 允许异步更新（最终一致），允许 ≤ 1s 的置顶/排序延迟。
- 压测仍需要跨实例（sender 与 receiver 经常不在同一实例），用于覆盖路由/跨实例 push。

## 变更内容

1. **把 `t_single_chat.updated_at` 从 ACK/投递关键路径拆出去（异步 best-effort）**
   - ACK(saved) 与在线投递仅依赖“消息落库成功”。
   - `updated_at` 更新走异步线程池 + 去抖，失败可跳过（不阻塞主链路）。

2. **减少 eventLoop 任务排队：串行队列在 inEventLoop 时直接执行**
   - 当前 `WsChannelSerialQueue` 即使在 eventLoop 内也会 `eventLoop().execute(...)`，会制造额外 pending tasks。
   - 改为：inEventLoop 直接执行 supplier，仅在非 eventLoop 时才 execute。

3. **修正压测 pinning 策略：把连接均匀分布到 5 实例，同时保持跨实例**
   - `rolePinned` 模式下，按 `userId mod N` 选择 wsUrl，实现稳定分布与跨实例（peer=userId±1，天然落到不同实例）。

## 影响范围

- **模块:**
  - `gateway/ws`（单聊落库链路、串行队列调度）
  - `scripts/ws-load-test`（压测连接分布策略）
- **文件:**
  - `src/main/java/com/miniim/gateway/ws/WsSingleChatHandler.java`
  - `src/main/java/com/miniim/gateway/ws/WsChannelSerialQueue.java`
  - `scripts/ws-load-test/WsLoadTest.java`
- **数据:**
  - `t_single_chat.updated_at` 更新从“强同步”变为“异步最终一致（≤1s 目标）”
- **API:**
  - 无新增/变更

## 核心场景

### 需求: 单聊主链路提速（延迟优先）
**模块:** gateway/ws + scripts

#### 场景: 5 实例 + open-loop（clients=5000, msgIntervalMs=3000）
- 条件：固定速率发送（open-loop），跨实例路由开启
- 预期：
  - `ws_perf single_chat.queueMs/dbQueueMs` 明显下降（p50 从秒级回到 <100ms 量级，或至少显著收敛）
  - E2E p50 回到 < 1s 量级（以你当前单机资源为基准，给出实测）
  - `wsError`（含 server_busy/写失败）保持在可接受范围（目标 < 5%）

### 需求: 会话列表置顶允许 ≤1s 最终一致
**模块:** domain/single_chat

#### 场景: 单聊消息保存成功后，会话列表短暂延迟置顶
- 条件：`updated_at` 异步更新、窗口去抖开启
- 预期：
  - 不影响消息落库与 ACK(saved)
  - 会话排序可能延迟，但在 ≤1s 内收敛（或在过载时允许更大延迟但不拖慢主链路）

## 风险评估

- **风险:** `updated_at` 异步任务积压/被拒绝，导致会话置顶滞后扩大
  - **缓解:** 继续沿用去抖（窗口内最多一次）；异步执行采用 best-effort（拒绝/失败可跳过并记录可观测事件），下一条消息会再次触发更新机会
- **风险:** 压测 pinning 行为变化导致历史数据对照不可直接比较
  - **缓解:** 在回归报告中明确标注“pinning 策略变更”，并以“offered load 相同 + 指标收敛”作为对照口径

