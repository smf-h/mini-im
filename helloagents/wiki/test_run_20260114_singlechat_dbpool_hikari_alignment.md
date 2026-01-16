# 单聊性能回归：DB 线程池 × JDBC 连接池对齐（5 实例，open-loop）

## 结论摘要

1. **瓶颈结论**：在 5 实例 / 5000 clients / open-loop（`msgIntervalMs=3000`）下，单聊主链路的主要瓶颈仍以 **DB executor 排队（`ws_perf single_chat.dbQueueMs`）** 为主；`saveMsgMs/pushMs` 均为次要开销。
2. **对齐有效**：把 `imDbExecutor` 的并发度与 Hikari `maximum-pool-size` **对齐**，并收紧 `queue-capacity`，可显著压低 `ws_perf single_chat.totalMs` 的 **P95/P99**，同时 E2E 的 **P50/P95/P99** 同步下降。
3. **过度并发有代价**：对齐到更大的并发（如 16）虽然进一步压低了服务端 `dbQueueMs/totalMs`，但会显著抬高 `wsErrorRate`、降低 `ackSavedRate/deliveredRate`，不符合你给的 `<5%` 过载阈值。

## 关键注意：避免 Redis 幂等污染（非常重要）

本项目的 `clientMsgId` 幂等是 Redis `SETNX`（TTL 默认 1800s）。压测脚本里 `clientMsgId = userId + "-" + seq`，**如果跨 run 复用同一批 `userId/clientMsgId`，后续 run 会大量命中幂等：出现“ACK(saved) 正常，但不落库/不投递，E2E 统计为空/偏乐观”的假象**。

为避免口径污染，本次对照在每组 run 都显式指定了不同的 `-UserBase`。另外我已在 `scripts/ws-cluster-5x-test/run.ps1` 增加默认的 `AutoUserBase`（若未显式传 `-UserBase`，会自动生成一个不易冲突的基座）。

## 测试场景与口径

- 集群：本机启动 5 实例（脚本自动分配 `server.port` 与 `im.gateway.ws.port`）
- 模式：open-loop 固定速率（避免“ACK 更快→压测更猛→数据更差”的闭环偏差）
- 单聊：`clients=5000`（偶数为发送者，奇数为接收者，成对互发：`peer = uid ± 1`）
- 负载：`msgIntervalMs=3000`，`duration=60s`，`repeats=3`
- E2E 口径：接收端收到 `type=SINGLE_CHAT`，从 body 中解析 `sendTs` 计算 `now-sendTs`
- 服务器分段：来自 `ws_perf`（采样/慢日志），本文用 `ws_perf_summary_gw1.json` 的统计代表性切片（不是全局聚合）

## 对照结果（可比 A/B）

| 组别 | 关键参数 | 客户端：ackSaved% / delivered% / wsError% | 客户端：E2E p50/p95/p99 (ms) | 服务端(gw1)：dbQueue p95/p99 (ms) | 服务端(gw1)：total p95/p99 (ms) | runDir |
|---|---|---:|---:|---:|---:|---|
| Baseline | DB: 8/32 queue=10000；JDBC: 默认 | 97.42% / 87.55% / 0.25% | 470 / 836 / 1052 | 1280 / 1458 | 1314 / 1481 | `logs/ws-cluster-5x-test_20260114_212413` |
| Align-12 ✅（推荐） | DB: 12/12 queue=500；JDBC: 12/12 timeout=500ms | 97.58% / 87.83% / 0.50% | 409 / 773 / 913 | 775 / 849 | 797 / 866 | `logs/ws-cluster-5x-test_20260114_213614` |
| Align-16 ⚠️（不推荐） | DB: 16/16 queue=2000；JDBC: 16/16 timeout=500ms | 87.20% / 78.41% / 10.65% | 398 / 801 / 977 | 654 / 883 | 719 / 892 | `logs/ws-cluster-5x-test_20260114_214816` |

### 解读

- **Baseline → Align-12**：E2E 与 `ws_perf` 的尾部（P95/P99）同步下降，且过载错误仍在可接受范围内（`wsError%≈0.50%`）。
- **Align-16**：虽然 `ws_perf` 更好，但 `wsError%≈10.65%`、`ackSaved%/delivered%` 明显下降，说明系统进入了“更快失败/更激进拒绝/或 DB/连接池抖动更明显”的状态，不符合目标（延迟优先但允许失败 <5%）。

## 推荐落地参数（先按此作为生产/压测默认）

建议先以 Align-12 作为“单聊常规负载（msgIntervalMs=3000）”的默认：

- `spring.datasource.hikari.maximum-pool-size=12`
- `spring.datasource.hikari.minimum-idle=12`
- `spring.datasource.hikari.connection-timeout=500`
- `im.executors.db.core-pool-size=12`
- `im.executors.db.max-pool-size=12`
- `im.executors.db.queue-capacity=500`

如需进一步压尾（P99）而不提升失败率，优先调参顺序建议：

1. 在 `12/12` 不变的前提下，**把 `queue-capacity` 从 500 → 1000**（降低 reject 但要观察 dbQueue 是否反弹）
2. 再考虑 `maximum-pool-size` 小步上调到 14（同时 db core/max 一起对齐），并观察 `wsError%` 是否仍 <5%

## 可复现命令（PowerShell）

> 说明：以下命令均显式指定 `-UserBase` 以避免 Redis 幂等污染；你也可以依赖脚本默认的 `AutoUserBase`。

Baseline（对照）：

```powershell
powershell -ExecutionPolicy Bypass -File "scripts/ws-cluster-5x-test/run.ps1" `
  -SkipBuild -SkipConnectLarge -Instances 5 -BaseHttpPort 18080 -BaseWsPort 19001 `
  -OpenLoop -Inflight 0 -MaxInflightHard 200 -MsgIntervalMs 3000 `
  -DurationConnectSeconds 20 -DurationSmallSeconds 60 -WarmupMs 1500 -Repeats 3 `
  -UserBase 30000000 -DbCorePoolSize 8 -DbMaxPoolSize 32 -DbQueueCapacity 10000
```

Align-12（推荐）：

```powershell
powershell -ExecutionPolicy Bypass -File "scripts/ws-cluster-5x-test/run.ps1" `
  -SkipBuild -SkipConnectLarge -Instances 5 -BaseHttpPort 18080 -BaseWsPort 19001 `
  -OpenLoop -Inflight 0 -MaxInflightHard 200 -MsgIntervalMs 3000 `
  -DurationConnectSeconds 20 -DurationSmallSeconds 60 -WarmupMs 1500 -Repeats 3 `
  -UserBase 40000000 `
  -DbCorePoolSize 12 -DbMaxPoolSize 12 -DbQueueCapacity 500 `
  -JdbcMaxPoolSize 12 -JdbcMinIdle 12 -JdbcConnectionTimeoutMs 500
```

Align-16（不推荐，展示“过度并发”的代价）：

```powershell
powershell -ExecutionPolicy Bypass -File "scripts/ws-cluster-5x-test/run.ps1" `
  -SkipBuild -SkipConnectLarge -Instances 5 -BaseHttpPort 18080 -BaseWsPort 19001 `
  -OpenLoop -Inflight 0 -MaxInflightHard 200 -MsgIntervalMs 3000 `
  -DurationConnectSeconds 20 -DurationSmallSeconds 60 -WarmupMs 1500 -Repeats 3 `
  -UserBase 50000000 `
  -DbCorePoolSize 16 -DbMaxPoolSize 16 -DbQueueCapacity 2000 `
  -JdbcMaxPoolSize 16 -JdbcMinIdle 16 -JdbcConnectionTimeoutMs 500
```

