# 2026-01-15 单聊专项回归（ensureMembers 去热路径）

本报告用于验证一项“DB 减负”改造：把 `t_single_chat_member` 的 `ensureMembers()` 从单聊发送主链路移除，避免每条消息都做 2 次 `exists(limit 1)` 查询，从而降低 DB executor 排队压力（`dbQueueMs`），改善 E2E 分位数。

说明：
- 同机 5 实例（共用本机 MySQL/Redis），属于“定位瓶颈/回归对比”，不能直接外推生产容量上限。
- `SINGLE_E2E` 的 delivered 口径是“接收端收到 `SINGLE_CHAT` 并解析到 body.sendTs”，**不包含**会话列表 `updated_at` 等副作用（这也是之前 `updatedAt` 去抖收益大的原因之一）。

---

## 1) 变更摘要

### 1.1 单聊发送主链路移除 `ensureMembers()`

- 位置：`src/main/java/com/miniim/gateway/ws/WsSingleChatHandler.java`
- 变化：DB 闭包中只做 `getOrCreateSingleChatId + save(message)`，不再做 `singleChatMemberService.ensureMembers(...)`。
- 预期收益：减少每消息 2 次 DB read（exists），在高 QPS 时降低连接池/DB CPU 占用，压 `dbQueueMs`。

### 1.2 delivered/read ACK 路径缺行兜底补建

- 位置：`src/main/java/com/miniim/domain/service/impl/SingleChatMemberServiceImpl.java`
- 变化：`markDelivered/markRead` 改为“先 update → 若 0 行影响再 exists 判断 → 不存在则 ensureMember + 重试 update”
- 预期收益：避免每次 ACK 都 `exists`（仅缺行场景触发补建），并确保历史缺行数据仍可恢复。

---

## 2) 常规负载（open-loop，MsgIntervalMs=3000）

目标：与上一轮可比切片对照（5 实例 / 5000 clients / open-loop / MsgIntervalMs=3000 / 60s / repeats=3）。

命令（关键参数）：
- `scripts/ws-cluster-5x-test/run.ps1 -Instances 5 -OpenLoop -MsgIntervalMs 3000 -DurationSmallSeconds 60 -DurationConnectSeconds 60 -Repeats 3 -DbCorePoolSize 12 -DbMaxPoolSize 12 -DbQueueCapacity 500 -JdbcMaxPoolSize 12 -JdbcMinIdle 12 -JdbcConnectionTimeoutMs 500`

结果（avg，3 次平均）：
- E2E：`logs/ws-cluster-5x-test_20260115_005945/single_e2e_5000_avg.json`
  - offered≈833.33 msg/s；delivered≈730.82 msg/s；deliver≈87.70%
  - ackSaved≈97.62%；wsError≈0.49%
  - p50/p95/p99(ms)：415.67 / 782.67 / 962

对照（上一轮对齐池参数的基线，3 次平均）：
- `logs/ws-cluster-5x-test_20260114_213614/single_e2e_5000_avg.json`
  - p50/p95/p99(ms)：408.67 / 773.33 / 913
  - wsErrorAvg：252.33（约 0.50%）

解读（客观结论）：
- 在“每会话约 3s 1 条”的常规负载下，本改造对 E2E 分位数提升 **不明显**（变化在噪声范围内）。
- 说明：`ensureMembersMs` 在该负载下本就不是主导项（主导仍是 `dbQueueMs`），因此移除它不会立刻带来数量级收益；它更可能在更高 QPS（或 DB 更接近饱和）时体现价值。

---

## 3) 大并发连接/消息评估（50k clients）

目标：在单机约束下评估 50k 级别的“连接上限/关键风险”。注意：这是**单机压测器发起连接**，会受到 Windows 端口/句柄等上限影响。

命令（关键参数）：
- `scripts/ws-cluster-5x-test/run.ps1 -Instances 5 -OpenLoop -MsgIntervalMs 3000 -DurationSmallSeconds 60 -DurationConnectSeconds 60 -Repeats 3 -EnableLargeE2e -LargeE2eRepeats 1 -LargeE2eDurationSeconds 60 -DbCorePoolSize 12 -DbMaxPoolSize 12 -DbQueueCapacity 500 -JdbcMaxPoolSize 12 -JdbcMinIdle 12 -JdbcConnectionTimeoutMs 500`

连接结果（60s）：
- `logs/ws-cluster-5x-test_20260115_005945/connect_50000.json`
  - connectOk=16249，connectFail=33751（单机未能建立 50k 全量连接）

消息结果（1 次，60s）：
- `logs/ws-cluster-5x-test_20260115_005945/single_e2e_50000_avg.json`
  - offered≈2572.60 msg/s
  - ackSaved≈79.15%，delivered≈48.76%，wsError≈18.27%
  - p50/p95/p99(ms)：788 / 1873 / 2231

解读（为什么 50k 很“差”）：
- 核心限制不是服务端“扛不住 50k”，而是单机压测端在 Windows 上很可能遇到本地资源上限（典型表现是 `connectOk≈16k` 这一量级）。
- SINGLE_E2E 的配对模型是 `uid(i)` → `uid(i)+1`，当大量连接失败时，“发送方在线但接收方不在线”的比例会上升，导致 delivered 显著下降（但 saved 仍可能成功）。
- 因此：50k 这组更像是“单机压测上限/风险揭示”，不能当作生产容量结论。

---

## 4) 结论与建议

### 4.1 结论
- 常规 5000 clients / 3s 频率下：ensureMembers 去热路径对延迟没有明显改善（属于“为更高 QPS 做减负”的改造）。
- delivered/read ACK 路径的缺行兜底改造是必要的：它让 `t_single_chat_member` 从“发送强依赖”变为“按需补齐 + 可恢复”。
- 50k 在单机 Windows 环境下无法建立全量连接：需要分布式压测器/多机才能验证真实 50k 连接下的服务端能力。

### 4.2 下一步（更高 ROI 的 DB 减负方向）
- 真正要压 `dbQueueMs`：优先优化更重的 DB 写放大（例如消息表 insert + 其他写路径），`ensureMembers` 这类“隐形 read”属于次级但可叠加的收益。
- 若你想把 ensureMembers 的收益从噪声中量化出来：建议做一轮更高 QPS 的 open-loop（例如 5k@1s 或更高 clients），并同时采集 DB 指标（连接池等待、慢查询、InnoDB 行锁/redo 等），再对比“去热路径前后”的 dbQueueMs 与 wsErrorRate 曲线。

