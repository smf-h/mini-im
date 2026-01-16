# 单机多实例（1~4）对照：AutoTuneLocalThreads（含修正 1 实例 DB 并发上限）

## 0. 结论（先说结论）

- 单机下 **1~2 实例**可以做到非常低的 E2E（本轮 p99 在 `~200ms` 内），并且 `wsError=0`。
- **3~4 实例**在同样 offered load 下会出现更明显的尾部抖动（p95/p99 可到 `~0.7s~1.3s`），且 4 实例曾出现一次 `internal_error`（复测后消失，偏“单机抖动/下游偶发”）。
- 与 `5~9` 对照一起看：单机并不存在“实例越多越快”，有明显拐点；更像是“线程/连接池配额 + 跨实例转发比例 + 单机抖动”共同决定尾延迟。

## 1. 固定口径（与 5~9 保持一致）

- 场景：`single_e2e`（接收端收到 `SINGLE_CHAT` 计入 E2E）
- clients=`5000`（2500 sender / 2500 receiver）
- open-loop（固定速率）：msgIntervalMs=`3000`
- durationSeconds=`60`
- inflight=`0`
- LoadDrainMs=`5000`（停止发送后留 5s 收集 3s timeout 的 ERROR）
- rolePinned：开启（避免偶数实例数分配偏置）

## 2. AutoTuneLocalThreads 的“1 实例修正”

之前版本对 `dbExecutor/jdbcMax` 做了 `max=6` 上限，导致 **Instances=1** 在本机 + MySQL 的组合下出现 DB 供给不足，触发 `ERROR reason=server_busy`（dbExecutor queue/reject）。

修正后规则：
- 总 DB 并发目标 `targetDbTotal ≈ cpu`（本机 `cpu=20` → `targetDbTotal=20`）
- 每实例 `db = ceil(targetDbTotal / Instances)`，并按实例数设置上限：
  - Instances<=2：max=12
  - Instances<=4：max=8
  - 其他：max=6
- `jdbcMax = db`（让“DB 线程数”和“连接池容量”对齐）

## 3. 压测结果（1~4）

> runDir 都在 `logs/<runDir>/`，可复盘 `single_e2e_5000_r1.json` 与 `single_e2e_5000_avg.json`。

| 实例数 | AutoTune（nettyWorker/db/jdbcMax） | E2E p50/p95/p99 (ms) | sent/s | recv/s | wsError | 备注 |
|---:|---:|---:|---:|---:|---:|---|
| 1 | 4 / 12 / 12 | 11 / 120 / 193 | 811.67 | 730.65 | 0 | runDir=`ws-cluster-5x-test_20260116_195910` |
| 2 | 4 / 10 / 10 | 9 / 50 / 137 | 783.50 | 705.05 | 0 | runDir=`ws-cluster-5x-test_20260116_200422` |
| 3 | 4 / 7 / 7 | 9 / 283 / 718 | 798.38 | 718.50 | 0 | runDir=`ws-cluster-5x-test_20260116_202812` |
| 4 | 4 / 5 / 5 | 9 / 891 / 1374 | 798.62 | 718.75 | 0 | runDir=`ws-cluster-5x-test_20260116_202201`（复测） |

补充（异常一次）：
- 4 实例曾出现过一次 `internal_error`（`wsError≈6136`，runDir=`ws-cluster-5x-test_20260116_201542`），复测后消失，推断与单机抖动/下游偶发有关。

## 4. 如何解读（单机单实例 vs 多实例）

- 1 实例没有跨实例 PUSH（不走 Redis Pub/Sub），路径更短，因此尾延迟更容易收敛；但需要给足 DB/JDBC 并发，否则会出现 `server_busy`。
- 2 实例在本轮口径下表现最好之一：每实例连接/业务压力更小，且跨实例比例还不至于显著放大尾抖动。
- 3/4 实例开始更依赖“跨实例转发 + 单机调度稳定性”，即使 p50 仍很低，p95/p99 会更敏感。

