# Instances=5..9（全量 ws_perf）对照：实例数是否继续压低单聊延迟（2026-01-15）

## 目的

在不改业务代码的前提下：

1) 解决 `ws_perf` “慢采样偏置”（只记录慢请求+抽样导致分位数偏慢）的口径问题：改为**全量记录**。  
2) 快速跑一轮 `Instances=5..9`（每组只跑 1 次），观察实例数增加对 E2E 与 `dbQueueMs/queueMs` 的影响。

## 重要口径说明（必须先看）

### 1) 为什么之前会出现 “ws_perf 的 dbQueue p50 > E2E p50”

`ws_perf` 的记录逻辑在 `WsSingleChatHandler.maybeLogPerf()`：默认仅记录 `totalMs >= slowMs` 的慢请求，且对快请求按 `sampleRate` 抽样；因此 `ws_perf_summary` 天然偏慢。  
本次通过 `-PerfTraceFull` 强制 `slowMs=0 + sampleRate=1.0`，避免偏置。

### 2) ⚠️ 偶数实例数（6/8）会出现“只有一半网关有 single_chat 样本”

`scripts/ws-load-test/WsLoadTest.java` 的 `--rolePinned` 选路是：

- 连接落点：`idx = userId % instances`
- `single_e2e` 中 sender 的 userId 是连续 userBase 的 **偶数序列**（步长=2）

当 `instances` 是偶数（如 6、8）时，步长 2 与 instances 有公因数 ⇒ sender 只会落在 `0,2,4,...` 的一半网关上。  
所以：

- 6 实例时 single_chat 只会在 3 个网关上产生 `ws_perf single_chat`（另 3 个为 0 样本）
- 8 实例时 single_chat 只会在 4 个网关上产生 `ws_perf single_chat`

这会让 6/8 的对照天然不公平：**“实例数变大”不等于“sender 网关变多”**，反而可能让每个 sender 网关更忙。

本报告对 `dbQueueMs` 的 min/max 统计只在 “有 single_chat 样本的网关” 上计算，并额外给出 `activeSenderGateways`。

## 测试配置（固定不变）

脚本：`scripts/ws-cluster-5x-test/run.ps1`

- clients=5000
- open-loop
- MsgIntervalMs=3000
- DurationSmallSeconds=30（只跑 1 次）
- DurationConnectSeconds=20
- Repeats=1
- SkipConnectLarge
- PerfTraceFull（slowMs=0, sampleRate=1.0）

命令模板：

`powershell -ExecutionPolicy Bypass -File scripts/ws-cluster-5x-test/run.ps1 -Instances {N} -SkipBuild -SkipConnectLarge -OpenLoop -MsgIntervalMs 3000 -DurationSmallSeconds 30 -DurationConnectSeconds 20 -Repeats 1 -PerfTraceFull`

## 结果汇总（Instances=5..9）

> 注：下表的 `dbQueueMs(min..max)` 为“有 single_chat 样本的网关”范围；偶数实例会出现 `activeSenderGateways < instances`（见上文）。

- Instances=5（runDir=`logs/ws-cluster-5x-test_20260115_211606/`）
  - E2E p50/p95/p99(ms)：`1064/1951/2344`
  - sentPerSec≈`833.33`，recvPerSec≈`572.03`，wsErrorAvg≈`3668`
  - activeSenderGateways=`5`
  - dbQueueMs p50(min..max)=`751..1055`，p95=`1218..1928`，p99=`1275..2478`；queue p99 max=`0`

- Instances=6（runDir=`logs/ws-cluster-5x-test_20260115_212324/`）
  - E2E p50/p95/p99(ms)：`1753/2862/3942`
  - sentPerSec≈`833.33`，recvPerSec≈`657.83`，wsErrorAvg≈`549`
  - activeSenderGateways=`3`（⚠️ 只用了 1/2 网关做 sender）
  - dbQueueMs p50(min..max)=`1163..1764`，p95=`1925..2978`，p99=`2206..3105`；queue p99 max=`447`

- Instances=7（runDir=`logs/ws-cluster-5x-test_20260115_213113/`）
  - E2E p50/p95/p99(ms)：`867/1689/2064`
  - sentPerSec≈`833.33`，recvPerSec≈`612.63`，wsErrorAvg≈`2630`
  - activeSenderGateways=`7`
  - dbQueueMs p50(min..max)=`598..756`，p95=`1049..1555`，p99=`1466..1982`；queue p99 max=`0`

- Instances=8（runDir=`logs/ws-cluster-5x-test_20260115_213853/`）
  - E2E p50/p95/p99(ms)：`1277/2206/2524`
  - sentPerSec≈`833.33`，recvPerSec≈`679.37`，wsErrorAvg≈`89`
  - activeSenderGateways=`4`（⚠️ 只用了 1/2 网关做 sender）
  - dbQueueMs p50(min..max)=`918..928`，p95=`1855..1931`，p99=`2043..2266`；queue p99 max=`0`

- Instances=9（runDir=`logs/ws-cluster-5x-test_20260115_214640/`）
  - E2E p50/p95/p99(ms)：`579/1314/1689`
  - sentPerSec≈`833.33`，recvPerSec≈`608.53`，wsErrorAvg≈`3117`
  - activeSenderGateways=`9`
  - dbQueueMs p50(min..max)=`332..539`，p95=`905..1371`，p99=`1041..1772`；queue p99 max=`0`

## 结论（针对“继续加实例能不能压延迟”）

1) 在这组“单机 + 单库 MySQL”的压测里，`Instances=5→9` **并非单调变好**，但 `Instances=9` 的确出现了更低的 E2E 与更低的 `dbQueueMs`（p50 range 332..539ms）。
2) `Instances=6/8` 的结果偏差很大，主要原因不是“实例更多反而更慢”，而是 **rolePinned + sender 偶数 userId** 导致 sender 只落在一半网关上，等价于“有效实例数没变甚至更少”。因此不能用 6/8 的数据证明实例数无效。
3) 即使在 `Instances=9`，`queueMs` 基本为 0，瓶颈更集中在 `dbQueueMs`（应用侧 DB 排队 + DB 本身吞吐/锁/IO）。继续加实例在单机环境里更可能把压力继续推向 DB/CPU，而不是无限下降。

## 下一步（如果你要继续用“加实例”验证极限）

为了避免 6/8 的“半网关无样本”问题，建议后续做一个小改动让 sender 均匀落点（例如 rolePinned 选路改成 `hash(userId) % instances` 或对 sender 使用 `userId/2` 再取模），再重跑 Instances=5..9 才能回答“实例数是否单调提升吞吐/降低延迟”。

