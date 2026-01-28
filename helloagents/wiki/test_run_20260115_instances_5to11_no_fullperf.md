# Instances=5..11（恢复默认 perf/60s）对照：实例数继续增加的拐点（2026-01-15）

> ⚠️ 历史记录：本文原本涉及 `ws_perf/perf-trace` 的采样口径对照；相关能力在后续版本已移除，本文仅保留 E2E 对照结论与“实例数/选路”分析（不可复现分段口径）。

## 你要求的变更点（对应本次实验）

1) **不再依赖分段打点**：本次仅以 E2E 端到端结果对照（原 `ws_perf/perf-trace` 分段口径在后续版本已移除）。  
2) **修复 6/8 偶数实例的落点偏差**：`rolePinned` 的选路从 `userId % N` 改为稳定哈希取模，避免 sender userId 步长=2 时只落半数网关。实现位置：`scripts/ws-load-test/WsLoadTest.java` 的 `pickWsUrl()`。  
3) **时长恢复为 60s**：不再显式传 `-DurationSmallSeconds 30`，脚本默认就是 60s（见 `scripts/ws-cluster-5x-test/run.ps1` 参数默认值）。

## 固定测试口径

脚本：`scripts/ws-cluster-5x-test/run.ps1`

- clients=5000
- open-loop
- MsgIntervalMs=3000
- DurationSmallSeconds=60（默认）
- DurationConnectSeconds=30（默认）
- Repeats=1（只跑 1 次）
- SkipConnectLarge

命令模板：

`powershell -ExecutionPolicy Bypass -File scripts/ws-cluster-5x-test/run.ps1 -Instances {N} -SkipBuild -SkipConnectLarge -OpenLoop -MsgIntervalMs 3000 -Repeats 1`

## 结果（Instances=5..11）

> 指标来源：每个 runDir 内的 `single_e2e_5000_avg.json`（本次 repeats=1，所以 avg=单次）。

- Instances=5：`logs/ws-cluster-5x-test_20260115_221936/`
  - E2E p50/p95/p99(ms)=`434/1262/1564`
  - recvPerSec≈`742.32`，wsErrorAvg=`255`

- Instances=6：`logs/ws-cluster-5x-test_20260115_222803/`
  - E2E p50/p95/p99(ms)=`311/883/1360`
  - recvPerSec≈`664.62`，wsErrorAvg=`5742`

- Instances=7：`logs/ws-cluster-5x-test_20260115_223622/`
  - E2E p50/p95/p99(ms)=`256/734/1093`
  - recvPerSec≈`613.87`，wsErrorAvg=`8738`

- Instances=8：`logs/ws-cluster-5x-test_20260115_224443/`
  - E2E p50/p95/p99(ms)=`294/808/1255`
  - recvPerSec≈`683.60`，wsErrorAvg=`4471`

- Instances=9：`logs/ws-cluster-5x-test_20260115_225308/`
  - E2E p50/p95/p99(ms)=`269/646/923`
  - recvPerSec≈`636.12`，wsErrorAvg=`7563`

- Instances=10：`logs/ws-cluster-5x-test_20260115_230147/`
  - E2E p50/p95/p99(ms)=`299/922/1848`
  - recvPerSec≈`671.72`，wsErrorAvg=`3673`

- Instances=11：`logs/ws-cluster-5x-test_20260115_231037/`
  - E2E p50/p95/p99(ms)=`289/982/1530`
  - recvPerSec≈`746.92`，wsErrorAvg=`255`

## 拐点结论（按你要求“继续加到出现拐点”）

- 以 **E2E p50** 为主：从 5→6→7 持续下降，**在 7 实例达到最小值 p50≈256ms**；之后 8/10/11 出现回升（8:294、10:299、11:289），因此本次对照的“拐点/最低点”可认为出现在 **Instances≈7**。

## 需要你注意的“解释限制”（为什么不是严格单调）

1) **单机多实例不是“真扩容”**：实例数增加会带来更多 JVM/线程/连接池争用；出现拐点是正常的。
2) **wsErrorAvg 波动很大**（6/7/8/9/10），这会影响 E2E 的有效样本与尾延迟稳定性：同样的 offered load 下，有的 run 更容易出现 ACK/连接异常。  
   - 这类波动通常与单机资源竞争（CPU/GC/磁盘/DB）有关，而不是“实例数越大一定越稳定”。
