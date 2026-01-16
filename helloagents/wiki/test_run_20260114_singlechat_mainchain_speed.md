# 单聊主链路提速回归（P50 回到 <1s）：updated_at 异步化 + 串行队列 inline + 5 实例均衡 pinning

> 目标：解决 open-loop（`msgIntervalMs=3000`）下 E2E p50 秒级的问题，把单聊主链路（落库→ACK(saved)→在线投递）拉回 <1s 量级，并给出可复现数据。

---

## 1. 结论摘要

### 1.1 常规负载（open-loop, msgIntervalMs=3000, clients=5000）
- **本轮结果（5 实例、重复3次平均）**：E2E `p50≈410ms`，`p95≈754ms`，`p99≈841ms`
- **offered load**：`attempted≈819 msg/s`，`sent≈819 msg/s`
- **错误**：`wsErrorAvg≈236.7`（需结合 `single_e2e_5000_r*.json` / gateway 日志细分原因）
- **ws_perf（gw-1 摘要）**：`queueMs p50=0ms`，`dbQueueMs p50≈536ms`，`dbToEventLoopMs p95≈95ms`

### 1.2 高压 burst（open-loop, msgIntervalMs=100, clients=5000）
- offered load 明显超过系统可持续吞吐：`attempted≈21870 msg/s`，`sent≈9200 msg/s`
- E2E 出现“排队型延迟”（可复现但不作为 SLO）：`p50≈34s`，`p95≈50s`，`p99≈53s`
- `ws_perf` 显示瓶颈在**连接级串行队列排队**（`queueMs p50≈13s`），DB 排队反而较小（`dbQueueMs p50≈38ms`）

---

## 2. 本轮改动点（与主链路速度直接相关）

### 2.1 服务端：updated_at 从主链路拆出（异步 best-effort）
- 位置：`src/main/java/com/miniim/gateway/ws/WsSingleChatHandler.java`
- 变化：
  - 主 DB 任务只做：幂等→chatId→成员→消息 INSERT
  - `t_single_chat.updated_at` UPDATE 改为：完成 ACK(saved)/push 后，提交到 `imPostDbExecutor` 异步执行
  - 仍保留 1s 去抖（`SingleChatUpdatedAtDebouncer`），但不再阻塞主链路

### 2.2 网关：串行队列在 inEventLoop 时不再额外 execute
- 位置：`src/main/java/com/miniim/gateway/ws/WsChannelSerialQueue.java`
- 目的：减少 eventLoop pending tasks，降低 `dbToEventLoopMs` 与整体 queueing delay

### 2.3 压测器：rolePinned 从“固定 0/1 实例”改为“按 userId 均衡分布到 N 实例”
- 位置：`scripts/ws-load-test/WsLoadTest.java`
- 变化：`rolePinned=true` 时使用 `wsUrlIndex = floorMod(userId, wsUrls.size())`
- 影响：5 实例压测不再只打到 2 个实例；同时由于 `peer=userId±1`，发送方与接收方天然跨实例

---

## 3. 可复现步骤（Windows PowerShell）

### 3.1 前置条件
- 本机 MySQL：`127.0.0.1:3306`
- 本机 Redis：`127.0.0.1:6379`
- 已 `mvn -DskipTests package` 构建出 `target/mini-im-0.0.1-SNAPSHOT.jar`

### 3.2 常规负载（msgIntervalMs=3000）
```powershell
powershell -ExecutionPolicy Bypass -File "scripts/ws-cluster-5x-test/run.ps1" `
  -SkipBuild `
  -SkipConnectLarge `
  -OpenLoop `
  -MsgIntervalMs 3000 `
  -DurationSmallSeconds 60 `
  -Repeats 3
```

本轮产物目录：
- `logs/ws-cluster-5x-test_20260114_174932/`
- 关键结果：
  - `logs/ws-cluster-5x-test_20260114_174932/single_e2e_5000_avg.json`
  - `logs/ws-cluster-5x-test_20260114_174932/ws_perf_summary_gw1.json`

### 3.3 高压 burst（msgIntervalMs=100）
```powershell
powershell -ExecutionPolicy Bypass -File "scripts/ws-cluster-5x-test/run.ps1" `
  -SkipBuild `
  -SkipConnectLarge `
  -OpenLoop `
  -MsgIntervalMs 100 `
  -DurationSmallSeconds 60 `
  -Repeats 3
```

本轮产物目录：
- `logs/ws-cluster-5x-test_20260114_180604/`
- 关键结果：
  - `logs/ws-cluster-5x-test_20260114_180604/single_e2e_5000_avg.json`
  - `logs/ws-cluster-5x-test_20260114_180604/ws_perf_summary_gw1.json`

---

## 4. 对照解读（为什么这轮能把 p50 拉回 <1s）

### 4.1 “秒级 p50”的直接成因：热点 + 排队
旧口径下 `rolePinned` 会把 sender/receiver 固定到 `wsUrls[0/1]`，在 5 实例场景会形成热点，放大：
- `ws_perf single_chat.dbQueueMs`：DB 线程池排队
- `ws_perf single_chat.queueMs`：连接串行队列排队

当这两段排队进入秒级后，E2E p50 就会被拖到秒级（即使单条 SQL 仍是毫秒级）。

### 4.2 本轮三件事分别解决什么
- **pinning 均衡**：从源头消除“只打 2 实例”的热点，让 offered load 分摊到 5 实例
- **updated_at 异步化**：把低优先级 UPDATE 从 ACK/投递关键路径移走，降低 DB 主任务平均耗时，从而降低 `dbQueueMs`
- **串行队列 inline**：减少 eventLoop 额外调度，降低 `dbToEventLoopMs` 与 eventLoop backlog

---

## 5. 后续建议（如果你要进一步压低 p95/p99）

1) **把 `dbQueueMs p50≈536ms` 再压下去**
- 优先检查 `imDbExecutor` 线程数与 Hikari 连接池是否匹配（避免“线程很多但都在等连接”）
- 在单机 5 实例压测时，CPU 争用也会把排队抬高，建议以“单实例/双实例”对照确认 CPU vs DB 的占比

2) **burst 的尾延迟治理要靠“可控失败/限流”，不是硬抗**
- 当 offered load 远大于可持续吞吐时，`queueMs` 会线性爬升并把 E2E 拉到几十秒
- 若目标是“尾延迟收敛”，应启用/收紧入站门禁（`inbound-queue`）并让客户端退避重试，把过载转成可控失败

