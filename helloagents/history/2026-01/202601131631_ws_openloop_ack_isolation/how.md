# 技术设计: 固定发送速率压测（open-loop）+ ACK 推进隔离（1s 最终一致）

## 技术方案

### 目标与验收

1. **对照公平：** 在 offered load（attempted/sent）相近的前提下，比较延迟与错误率；避免“ACK 更快→压测发更猛→指标变差”的误判。
2. **尾延迟优先：** 以 `ws_perf single_chat.dbQueueMs/queueMs/totalMs` 与 E2E P95/P99 为主指标。
3. **最小改动：** 不改变 WS 协议，不引入 MQ；只做压测工具与 ACK 推进隔离/去抖。

---

## 1) 压测：open-loop 发送模式（固定速率）

### 现状问题（closed-loop 耦合）
当前 `WsLoadTest` 的发送端在固定 tick 上仍会受 inflight 门禁影响：ACK 变快会让 inflight 更快回落，实际发送更接近“满速”；因此不同版本在 60s 内的 sent/ackSaved 会变化，导致对照不公平。

### 设计（推荐）
新增 open-loop（固定速率）发送策略：

- **发送尝试固定：** 每个 sender 按 `msgIntervalMs` 固定节奏“尝试发送”，不以 inflight 作为节流依据。
- **安全门禁（避免压测端爆内存）：** 允许配置 `maxInflightHard`（仅作为压测端自保），超过则记录 `skippedHard` 并跳过该次发送（不改变下次调度节奏）。
- **输出口径拆分：**
  - `attempted` / `attemptedPerSec`：理论 offered load
  - `sent` / `sentPerSec`：实际写入 WebSocket 的次数
  - `skippedHard`：因压测端自保跳过（说明系统已经无法消化该 offered load）
  - `wsError`：sendText future 异常计数

> 对照原则：对比两次 run 时，应优先选择 attempted/sent 接近的 case；若出现大量 skippedHard，则说明 offered load 已超过系统可承载范围，延迟分位数将失真（此时应降低负载或改看“拒绝/错误率”）。

### 文件与参数
- `scripts/ws-load-test/WsLoadTest.java`：增加 `--openLoop`、`--maxInflightHard`
- `scripts/ws-load-test/run.ps1`：透传 `-OpenLoop/-MaxInflightHard`
- `scripts/ws-cluster-5x-test/run.ps1`：在 single_e2e 步骤透传 open-loop 参数（用于 5x 一键回归）

### 推荐的 open-loop 负载口径（便于复现）
以 `clients=5000`（其中 50% 为 sender）为例：
- 若希望接近“50% 用户每 3 秒 1 条”，建议设置 `MsgIntervalMs=3000`（理论 offered ≈ 2500/3 ≈ 833 msg/s）
- 若希望更强压：可下探到 `MsgIntervalMs=1000`（理论 offered ≈ 2500 msg/s）

open-loop 的核心是：用 `MsgIntervalMs` 控制 offered load，而不是用 inflight/ACK 的快慢间接控制。

---

## 2) 服务端：ACK 推进隔离（1s 最终一致）

### 现状与问题
`WsAckHandler` 当前使用 `imDbExecutor` 执行：
- 查消息（`messageService.getById/getOne`）
- 更新 delivered/read 游标（single_chat_member / group_member）
-（可选）给发送方推送 read/delivered ACK

这会与 `SINGLE_CHAT` 落库共用 DB 线程池，从线程排队视角直接抬高 `dbQueueMs`，并通过串行队列放大到 E2E 尾延迟。

### 设计（推荐：两段式）

#### 2.1 线程池隔离（先做，收益稳定）
- 新增 `imAckExecutor`（独立 ThreadPoolTaskExecutor）
- `WsAckHandler` 的异步逻辑改为跑在 `imAckExecutor`，避免占用 `imDbExecutor` 的排队槽位

#### 2.2 1s 合并/去抖（在隔离基础上做，进一步减写）
在你允许 1s 最终一致前提下：
- 对同一 `(ackUserId, chatType, chatId, ackType)` 在 1s 窗口内只保留 **最大 msgId**
- 每 1s flush 一次：批量执行 `markDelivered/markRead`
- read/delivered 回执给发送方也在 flush 时发送“游标式 ACK”（最大 serverMsgId），减少推送放大

关键约束：
- 只能“推进游标”，不能回退（用 MAX 语义保证幂等）
- flush 队列要有上限；超限时降级为丢弃非关键回执（但 DB 推进尽量保留）

### 文件与配置
- `src/main/java/com/miniim/config/ImAckExecutorProperties.java`（新增）
- `src/main/java/com/miniim/config/ImExecutorsConfig.java`：新增 `@Bean("imAckExecutor")`
- `src/main/java/com/miniim/gateway/ws/WsAckHandler.java`：使用 `imAckExecutor`；引入 1s 合并器（新组件或内部类）
- 文档：`helloagents/wiki/modules/gateway.md`、`helloagents/wiki/testing.md`

---

## 3) 可观测与回归

### 指标口径
- `ws_perf single_chat.dbQueueMs/queueMs/totalMs`：压主链路尾延迟
- `single_e2e_5000.json`：E2E P50/P95/P99（在 open-loop 下更可比）
- 新增（建议）：`ws_perf ack`：ackQueueMs/ackDbMs/ackPushMs

### 回归脚本（PowerShell）
- 5 实例对照：`scripts/ws-cluster-5x-test/run.ps1`（开启 open-loop 参数）
- 定点 ACK 压力：`scripts/ws-load-test/run.ps1 -Mode single_e2e ...`（配合客户端 ACK 行为）
