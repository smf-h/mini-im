# 5 实例消融：大优化来自“压测发送模型”还是“线程/连接池配置”

## 0. 结论（先说结论）

- 这轮“p50 只有几毫秒”的**主要原因是压测发送模型从 burst（齐发微突发）变成 spread（均匀摊平）**，而不是线程数本身。
- `AutoTuneLocalThreads` 的作用更偏“**压尾（p95/p99）**”，在 `spread` 下能进一步把尾部压低；但它无法单独把 `burst` 的排队型尾延迟消掉（反而可能更慢）。
- 之前出现的 `internal_error` 大量飙升，根因是 **MyBatis-Plus ASSIGN_ID（Snowflake）在单机多 JVM 下发生 ID 冲突**导致 `DuplicateKeyException`；已通过“每实例固定 workerId + 提前初始化 IdWorker/IdentifierGenerator”消除（见第 3 节）。

## 1. 固定测试口径（本文件所有对照保持一致）

- 环境：Windows 单机；MySQL `127.0.0.1:3306`、Redis `127.0.0.1:6379`；网关多 JVM 多实例。
- 实例数：`Instances=5`
- 场景：单聊 `SINGLE_E2E`（发送者发 → 接收者收到 `SINGLE_CHAT` 计入 E2E）
- 压测模型：`open-loop`
- clients=`5000`（2500 sender / 2500 receiver）
- msgIntervalMs=`3000`
- durationSeconds=`60`
- repeats=`1`

## 2. 2×2 对照结果（5 实例）

| 维度 | runDir | E2E p50/p95/p99 (ms) | sent/s | recv/s | wsError |
|---|---|---:|---:|---:|---:|
| AutoTune=OFF, sendModel=burst | `logs/ws-cluster-5x-test_20260116_223836/` | 324 / 736 / 1441 | 833.33 | 750.00 | 0 |
| AutoTune=OFF, sendModel=spread | `logs/ws-cluster-5x-test_20260116_224422/` | 9 / 111 / 725 | 797.53 | 717.88 | 0 |
| AutoTune=ON, sendModel=burst | `logs/ws-cluster-5x-test_20260116_225009/` | 539 / 1657 / 2267 | 833.33 | 750.00 | 0 |
| AutoTune=ON, sendModel=spread | `logs/ws-cluster-5x-test_20260116_223222/` | 9 / 74 / 331 | 798.02 | 718.23 | 0 |

读法（如何判断“主要原因”）：
- **只切发送模型（OFF+burst → OFF+spread）**：p50 `324→9ms`、p95 `736→111ms`，这是数量级变化，说明“大优化主要来自 sendModel”。
- **只切线程配置（OFF+spread → ON+spread）**：p95 `111→74ms`、p99 `725→331ms`，属于“压尾”，不是把 p50 从秒级打到毫秒级的那种变化。
- `burst` 是“每 3 秒齐发一次”的微突发，会把吞吐压力挤到极短时间窗口里，天然更容易触发排队；`spread` 更接近真实用户行为（请求随机到达）。

## 3. 关键修复：消除单机多 JVM 的 Snowflake ID 冲突

现象：
- 压测中 `wsError` 的 `internal_error` 对应网关日志里的 `DuplicateKeyException`（例如 `t_message.PRIMARY`、`t_single_chat.PRIMARY`），本质是 ID 冲突导致插入失败 → 返回 `ERROR internal_error`。

修复：
- 网关启动时**更早**初始化 MyBatis-Plus ID 生成器，并提供同一套 `IdentifierGenerator`：
  - `src/main/java/com/miniim/config/IdWorkerConfig.java`
- 单机多实例脚本为每个实例注入唯一 workerId：
  - `scripts/ws-cluster-5x-test/run.ps1`

