# 群聊 updated_at 位置 A/B（post_db vs inline_db）

目的：验证“群聊 `t_group.updated_at` 更新”是否属于群聊 E2E 的关键路径，以及把它从主链路移到 `postDbExecutor`（并去抖）是否带来可观收益。

口径（两次对照保持一致）：
- 5 实例，`AutoTuneLocalThreads=true`
- open-loop（单聊 `MsgIntervalMs=3000`，`repeats=3` 自动平均）
- 群聊（`clients=200`，`senders=20`，`msgIntervalMs=50`，`duration=60s`）
- `LoadSendModel=spread`
- 仅改变：`im.gateway.ws.group.updated-at.mode`

## A：`GroupUpdatedAtMode=post_db`（异步 + 去抖）

runDir：`logs/ws-cluster-5x-test_20260117_202113/`

群聊（`group_push_e2e.json`）：
- sentPerSec=`403.67`
- wsError=`0`
- E2E p50/p95/p99=`21/34/498 ms`

单聊（5000，`single_e2e_5000_avg.json`）：
- sentPerSecAvg=`797.58`
- wsErrorAvg=`0`
- E2E p50/p95/p99=`9/72/158.33 ms`

## B：`GroupUpdatedAtMode=inline_db`（同步在主链路）

runDir：`logs/ws-cluster-5x-test_20260117_203402/`

群聊（`group_push_e2e.json`）：
- sentPerSec=`403.67`
- wsError=`0`
- E2E p50/p95/p99=`22/36/529 ms`

单聊（5000，`single_e2e_5000_avg.json`）：
- sentPerSecAvg=`792.35`
- wsErrorAvg=`0`
- E2E p50/p95/p99=`8.67/47.33/113 ms`

## 结论

- 在当前“200 人群、20 sender、50ms”的 baseline 负载下，群聊 E2E 主要不被 `updated_at` 更新主导：`post_db` 与 `inline_db` 的 p50/p95/p99 差异很小（几十毫秒以内，p99 约 0.5s 量级）。
- ⚠️ 注意：这两次对照只各跑 1 次；单聊的 p95/p99 在两次 run 之间有明显波动，说明“跨 run 抖动”仍存在，所以本结论只对群聊 baseline 的“量级判断”成立（是否要做更严格消融，建议固定更长时长或重复数）。

