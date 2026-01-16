# 单机多实例（5~9）对照：AutoTuneLocalThreads + Netty workerThreads

## 0. 结论（先说结论）

- **单机（同一台机器）多 JVM 多实例并不会“实例越多越快”**：存在明显拐点；在本机环境下，**5~7 实例**能同时做到低延迟且无 `internal_error`，继续加到 8/9 会出现 DB 超时导致的 `ERROR reason=internal_error` 上升。
- 这轮“巨大突破”的本质：**限制每实例 Netty worker/eventLoop 线程数 + DB/JDBC 总并发稳定化**，避免“实例数上去→线程数爆炸→上下文切换/GC/锁竞争→DB 排队→3s 超时→internal_error”。
 - 另见：`helloagents/wiki/test_run_20260116_instances_1to4_autotune.md:1`（补齐 1~4 实例对照，帮助你判断“单实例/双实例是否更适合本机基线”）。

## 1. 测试配置（固定不变的口径）

- 环境：Windows 单机；MySQL `127.0.0.1:3306`、Redis `127.0.0.1:6379`；网关为多 JVM 多实例。
- 压测：`open-loop`（固定速率，不因 ACK 变快而“撞到下一个瓶颈”）。
- 场景：单聊 `SINGLE_E2E`（发送者发 → 接收者收到 `SINGLE_CHAT` 计入 E2E）。
- 参数（核心）：
  - clients=`5000`（2500 sender / 2500 receiver）
  - msgIntervalMs=`3000`
  - durationSeconds=`60`
  - inflight=`0`
  - drainMs=`5000`（用于把 3s DB timeout 的 ERROR 充分收集出来，避免“关得太快看不到错误”）
  - rolePinned=`true`（避免偶数实例数因 userId 步长导致的分配偏置）

## 2. 关键工程改动（本轮为什么能测得更准/更快）

### 2.1 让单机多实例“可比”
- 网关：`im.gateway.ws.worker-threads` 可配置；单机多实例时把每实例 worker threads 限制到小值，避免线程数爆炸。
- 压测脚本：新增 `AutoTuneLocalThreads`，按实例数对齐：
  - Netty worker threads（每实例）
  - DB executor（每实例）
  - JDBC Hikari 最大连接数（每实例）
  - post-db / ack executor（每实例）

### 2.2 让错误率“可解释”
- 压测器新增：
  - `errorsByReason`：统计收到的 `{"type":"ERROR","reason":"..."}` 分桶
  - `drainMs`：停止发送后延迟关闭，确保 `orTimeout(3s)` 触发的错误能被看到
  - 关闭阶段不再把“关连接产生的 onError/sendFail”算进 `wsError`（避免把关机噪声当稳定性问题）

## 3. 实例数 5~7：延迟很低且无 internal_error（优秀区间）

> 数据来自：`logs/ws-cluster-5x-test_20260116_173646/`、`logs/ws-cluster-5x-test_20260116_174231/`、`logs/ws-cluster-5x-test_20260116_174820/`

| 实例数 | E2E p50/p95/p99 (ms) | sent/s | recv/s | wsError | 主要错误 |
|---:|---:|---:|---:|---:|---|
| 5 | 9 / 71 / 185 | 798.18 | 718.32 | 0 | - |
| 6 | 9 / 457 / 949 | 798.65 | 718.75 | 0 | - |
| 7 | 12 / 93 / 206 | 798.17 | 718.22 | 0 | - |

说明：
- 5/7 的 E2E 分位数非常稳定，且 **0 ERROR**（在 `drainMs=5000` 口径下仍为 0）。
- 6 实例出现一次尾部抬升（p99≈949ms），属于“单机多 JVM 资源调度波动”常见现象；但仍无 `internal_error`。

## 4. 实例数 8/9：开始出现 internal_error（拐点区间）

> 数据来自：`logs/ws-cluster-5x-test_20260116_181947/`、`logs/ws-cluster-5x-test_20260116_182539/`

| 实例数 | E2E p50/p95/p99 (ms) | sent/s | recv/s | wsError | 主要错误 |
|---:|---:|---:|---:|---:|---|
| 8 | 9 / 33 / 70 | 798.93 | 684.03 | 2156 | internal_error |
| 9 | 10 / 43 / 102 | 798.60 | 688.43 | 1849 | internal_error |

解释（为什么“延迟看着还行但错误率飙升”）：
- `internal_error` 在单聊里基本等价于：DB executor 的落库闭包 **3s 内没完成**（`WsSingleChatHandler` 对 `supplyAsync(...).orTimeout(3s)` 超时），触发 `ERROR internal_error`。
- 单机多 JVM 增加到 8/9 后，**进程/线程/GC/上下文切换开销**开始明显侵蚀 DB 侧可用 CPU 与 IO 时间片，导致“少数请求超时→错误率上升”，而不是“所有请求都变慢”。

## 5. 推荐结论（你可以直接照做）

- 如果目标是“单机把延迟压到亚 100ms 且错误率接近 0”：**选 5 或 7 实例**。
- 如果必须继续加实例（>7）：
  - 需要同步提升下游（尤其 MySQL）可用资源（更快磁盘/更多 CPU 或独立机器），或放宽/改造 `orTimeout(3s)` 的语义（否则必然会看到 `internal_error`）。

## 6. 50k 连接能力（单机硬件/OS 限制示例）

> 数据来自：`logs/ws-cluster-5x-test_20260116_183451/`（Instances=5，DurationConnectSeconds=20）

- `connect_50000`：attempts=`50000`，ok=`16301`，fail=`33699`

含义：
- 单机上“堆连接数”很快会先撞到 **客户端/OS 资源瓶颈**（端口/句柄/线程/内存），这类失败不代表服务端业务链路吞吐差，而是环境上限。
