# 单机多实例：秒级→亚秒级是否“主要由实例数导致”的对照验证（2026-01-15）

## 目的

验证“单聊 E2E 从秒级到亚秒级”的巨大变化，是否主要由**实例数/实例配置**导致（而不是代码改动导致）。

## 结论摘要（先给结论）

- **在单机环境**下，实例数确实可能带来“秒级→亚秒级”的巨大变化，但这往往不是“均衡”本身，而是**隐含的总资源扩容**：实例数增加 = JVM/Netty 线程/DB executor/连接池总量叠加。
- 当我们把“总 DB 资源（DB executor 线程数 + JDBC 连接数）”近似固定后，**5 实例并没有明显优于 1 实例**，甚至更差：说明“仅靠加实例”不是根因解法，真正的关键仍是 `dbQueueMs`/eventLoop backlog 的治理与 DB 写放大控制。

## 测试环境与口径

- 环境：单机 Windows（同一台机器上起多实例），本地 MySQL + Redis。
- 压测脚本：`scripts/ws-cluster-5x-test/run.ps1`（内部调用 `scripts/ws-load-test/run.ps1` 的 `single_e2e`）。
- 场景（固定）：`clients=5000`、`open-loop`、`MsgIntervalMs=3000`、`DurationSmallSeconds=30`、`DurationConnectSeconds=20`、`Repeats=1`、`SkipConnectLarge`。
- E2E 口径：`sendTs → recvTs`（接收端收到 `SINGLE_CHAT` 计入 E2E），不包含会话列表刷新等副作用。

## 对照实验 1：默认配置（实例数变化 = 总资源也变化）

### A1：Instances=1（默认 DbCorePoolSize=8/DbMaxPoolSize=32；JDBC 使用 Spring 默认）

命令：

`powershell -ExecutionPolicy Bypass -File scripts/ws-cluster-5x-test/run.ps1 -SkipBuild -Instances 1 -OpenLoop -MsgIntervalMs 3000 -DurationSmallSeconds 30 -DurationConnectSeconds 20 -Repeats 1 -SkipConnectLarge`

结果目录：

- `logs/ws-cluster-5x-test_20260115_202441/`

关键结果（5000 clients）：

- E2E：p50/p95/p99 ≈ `6724/10598/11784 ms`
- sentPerSec≈`833.33`，recvPerSec≈`393.57`，wsErrorAvg≈`3310`
- ws_perf（gw1, single_chat）：`dbQueueMs p50≈2919ms`，`queueMs p50≈2329ms`，`dbToEventLoopMs p95≈1556ms`

### A2：Instances=5（同样“每实例默认配置”，总量叠加）

命令：

`powershell -ExecutionPolicy Bypass -File scripts/ws-cluster-5x-test/run.ps1 -SkipBuild -Instances 5 -OpenLoop -MsgIntervalMs 3000 -DurationSmallSeconds 30 -DurationConnectSeconds 20 -Repeats 1 -SkipConnectLarge`

结果目录：

- `logs/ws-cluster-5x-test_20260115_202944/`

关键结果（5000 clients）：

- E2E：p50/p95/p99 ≈ `451/1049/1265 ms`
- sentPerSec≈`833.33`，recvPerSec≈`703.13`，wsErrorAvg≈`255`
- ws_perf（gw1, single_chat）：`dbQueueMs p50≈713ms`，`queueMs≈0`，`dbToEventLoopMs p99≈31ms`

解释：

- 5 实例明显更快，且 `dbQueueMs/queueMs/dbToEventLoopMs` 都显著下降。
- 但这组对照并不能说明“均衡”本身是根因，因为实例数增加会导致 **DB executor 线程总量、JDBC 连接池总量、Netty 线程总量** 都随之上升（单机等于“隐式扩容”）。

## 对照实验 2：近似固定“总 DB 资源”（把 per-instance 降下来）

目标：让 1 实例与 5 实例的 DB executor 线程数、JDBC 连接数总量尽可能接近，从而观察“仅靠实例数/均衡”是否还能带来同量级收益。

### B1：Instances=1（DbCore=10/DbMax=10；JDBC=10）

命令：

`powershell -ExecutionPolicy Bypass -File scripts/ws-cluster-5x-test/run.ps1 -SkipBuild -Instances 1 -OpenLoop -MsgIntervalMs 3000 -DurationSmallSeconds 30 -DurationConnectSeconds 20 -Repeats 1 -SkipConnectLarge -DbCorePoolSize 10 -DbMaxPoolSize 10 -DbQueueCapacity 500 -JdbcMaxPoolSize 10 -JdbcMinIdle 10 -JdbcConnectionTimeoutMs 500`

结果目录：

- `logs/ws-cluster-5x-test_20260115_203538/`

关键结果（5000 clients）：

- E2E：p50/p95/p99 ≈ `1333/2131/2335 ms`
- ws_perf：`dbQueueMs p50≈333ms`，`queueMs≈0`，`dbToEventLoopMs p50≈616ms`

### B2：Instances=5（DbCore=2/DbMax=2；JDBC=2，每实例都更小）

命令：

`powershell -ExecutionPolicy Bypass -File scripts/ws-cluster-5x-test/run.ps1 -SkipBuild -Instances 5 -OpenLoop -MsgIntervalMs 3000 -DurationSmallSeconds 30 -DurationConnectSeconds 20 -Repeats 1 -SkipConnectLarge -DbCorePoolSize 2 -DbMaxPoolSize 2 -DbQueueCapacity 500 -JdbcMaxPoolSize 2 -JdbcMinIdle 2 -JdbcConnectionTimeoutMs 500`

结果目录：

- `logs/ws-cluster-5x-test_20260115_204023/`

关键结果（5000 clients）：

- E2E：p50/p95/p99 ≈ `1689/3223/3549 ms`
- ws_perf：`dbQueueMs p50≈1401ms`，`queueMs p99≈145ms`，`dbToEventLoopMs p95≈122ms`

解释：

- 当我们把 per-instance 的 DB 资源压低后，5 实例并没有继续保持亚秒级优势，甚至更慢（`dbQueueMs` 变成主因）。
- 这说明“实例数”能带来巨大收益的前提是：**你真的在用更多总资源（或你在多机环境里确实有更多总资源）**。

## 回答核心问题：巨大突破是不是“实例造成的”

在单机测试里：

- 如果你用“默认配置”，从 1 实例切到 5 实例确实能出现“秒级→亚秒级”的变化（本报告 A1→A2 就是）。
- 但这主要是因为**总资源扩容**（DB executor、JDBC、Netty 线程等叠加），而不是“均衡”本身的魔法。
- 如果你把总资源近似固定，实例数变化本身不会带来同量级收益（本报告 B1→B2）。

