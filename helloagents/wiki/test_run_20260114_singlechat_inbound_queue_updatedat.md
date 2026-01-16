# 2026-01-14 单聊专项回归（入站队列上限 + updatedAt 去抖）

本报告用于回答两个问题：
1) 入站串行队列上限（`im.gateway.ws.inbound-queue.*`）对尾延迟/错误率的影响；
2) `t_single_chat.updated_at` 去抖（1s 窗口）在 burst 场景是否能显著降低写放大并改善尾延迟。

说明：同机 5 实例（共用本机 MySQL/Redis），属于“定位瓶颈/回归对比”，不能直接外推生产容量上限。

---

## 1) 常规负载（open-loop，MsgIntervalMs=3000）

### 1.1 入站队列上限 = 2（更激进门禁）

命令（关键参数）：
- `scripts/ws-cluster-5x-test/run.ps1 -Instances 5 ... -MsgIntervalMs 3000 -OpenLoop -InboundQueueEnabled -InboundQueueMaxPendingPerConn 2`

结果：
- E2E（avg）：`logs/ws-cluster-5x-test_20260114_002954/single_e2e_5000_avg.json`
  - p50/p95/p99(ms)：4512.67 / 8318.67 / 9471
  - wsErrorAvg：5855
- 分段（gw1）：`logs/ws-cluster-5x-test_20260114_002954/ws_perf_summary_gw1.json`
  - single_chat.queueMs p50/p95/p99(ms)：1131 / 4135 / 4855
  - single_chat.dbQueueMs p50/p95/p99(ms)：2613 / 4111 / 4192

解读：
- 门禁“过小”时，会显著增加失败（wsError），且对本场景（每会话 3s 一条）并不能改善 DB 排队（dbQueueMs 仍高）。

### 1.2 入站队列上限 = 500（更宽松）

命令（关键参数）：
- `scripts/ws-cluster-5x-test/run.ps1 -Instances 5 ... -MsgIntervalMs 3000 -OpenLoop -InboundQueueEnabled -InboundQueueMaxPendingPerConn 500`

结果：
- E2E（avg）：`logs/ws-cluster-5x-test_20260114_153056/single_e2e_5000_avg.json`
  - p50/p95/p99(ms)：6951 / 11561.67 / 12170.33
  - wsErrorAvg：3158.67
- 分段（gw1）：`logs/ws-cluster-5x-test_20260114_153056/ws_perf_summary_gw1.json`
  - single_chat.queueMs p50/p95/p99(ms)：2441 / 9170 / 11498
  - single_chat.dbQueueMs p50/p95/p99(ms)：3295 / 4938 / 6292

解读：
- “常规负载”下的主问题仍是排队（queueMs/dbQueueMs），而不是单条 SQL 执行时间（saveMsgMs/updateChatMs 仍为 ms 级）。
- `updatedAt` 去抖在该场景收益不明显：因为压测模型是“一对一固定配对”，每个 `singleChatId` 约 3 秒才一条消息，本就不会形成 1s 窗口内的多次更新。

---

## 2) burst 场景（open-loop，MsgIntervalMs=100）

目的：模拟同一会话短时间内高频消息，验证 `t_single_chat.updated_at` 去抖是否能明显减少写放大并改善尾延迟。

为避免额外噪声，本轮跳过 50k connect：`scripts/ws-cluster-5x-test/run.ps1 -SkipConnectLarge ...`

### 2.1 去抖开启（debounceEnabled=true, window=1000ms）

结果：
- E2E（avg）：`logs/ws-cluster-5x-test_20260114_160242/single_e2e_5000_avg.json`
  - attempted/sentPerSecAvg：20856.1 msg/s
  - p50/p95/p99(ms)：8524.67 / 12881.67 / 13096
  - wsErrorAvg：3146
- 分段（gw1）：`logs/ws-cluster-5x-test_20260114_160242/ws_perf_summary_gw1.json`
  - updateChatMs p50/p95/p99(ms)：0 / 2 / 5（大量 update 被跳过）
  - dbQueueMs p50/p95/p99(ms)：130 / 2109 / 3447

### 2.2 去抖关闭（debounceEnabled=false）

结果：
- E2E（avg）：`logs/ws-cluster-5x-test_20260114_161638/single_e2e_5000_avg.json`
  - attempted/sentPerSecAvg：20887.37 msg/s
  - p50/p95/p99(ms)：123149.33 / 128991 / 133353
  - wsErrorAvg：2470.67
- 分段（gw1）：`logs/ws-cluster-5x-test_20260114_161638/ws_perf_summary_gw1.json`
  - updateChatMs p50/p95/p99(ms)：5 / 11 / 24（每条消息均更新会话）
  - dbQueueMs p50/p95/p99(ms)：1181 / 2410 / 4431

结论（burst 场景清晰可见）：
- `t_single_chat.updated_at` 的“每条消息都更新”会显著放大写路径压力，导致排队放大到分钟级（E2E p50 约 123s）。
- 1s 去抖后，`updateChatMs` 大部分为 0，`dbQueueMs p50` 从 1181ms 降到 130ms，并把 E2E p50 从 ~123s 拉回 ~8.5s（数量级改善）。

---

## 3) 建议的下一步（基于本轮结果）

- `updatedAt` 去抖建议保留默认开启（window=1000ms）；它在 burst 形态下的收益非常明确。
- 入站队列上限不建议“一刀切设很小”（例如 2），更建议从 200~500 起步，以 `wsErrorRate < 5%` 为约束逐步收紧；并结合客户端退避 + 抖动重试策略做全链路回归。

