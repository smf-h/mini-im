# 2026-01-15 单聊“两级回执”（ACK accepted/saved）回归与评估

本报告用于验证一项“工程化升级”改造：将单聊的发送回执拆为两级：

- `ACK(accepted)`：表示消息已进入可靠队列（Redis Streams），用于降低“发送端确认”的延迟下界（不再等 DB commit）。
- `ACK(saved)`：表示消息已完成 DB 落库（与原语义一致），用于历史查询/最终一致。

同时支持一项可选增强：`deliverBeforeSaved=true` 时允许“对端先看到消息，再异步落库并补发 ACK(saved)”（最终一致）。

说明：
- 本报告的压测口径是 `scripts/ws-load-test` 的 `single_e2e`：接收端收到 `SINGLE_CHAT` 即计入 E2E（sendTs→recvTs），**不包含**会话列表更新等副作用。
- 同机 5 实例（共用本机 MySQL/Redis），属于“定位瓶颈/回归对比”，不能直接外推生产容量上限。

---

## 0) 环境准备（Windows / PowerShell）

### 0.1 Redis（本机未安装时的可复现方案）

本轮压测使用 Memurai（Redis 7.2 兼容）“免安装提取”方式启动（避免安装器在该机环境失败）：

1) 下载 MSI（如可联网）：
- `Invoke-WebRequest -Uri "https://dist.memurai.com/releases/Memurai-Developer/4.1.2/Memurai-Developer-v4.1.2.msi" -OutFile "$env:TEMP\\Memurai-Developer-v4.1.2.msi"`

2) 解包 MSI（不执行安装器自定义动作）：
- `New-Item -ItemType Directory -Force -Path "C:\\Temp\\memurai_portable" | Out-Null`
- `Start-Process msiexec.exe -ArgumentList "/a `"$env:TEMP\\Memurai-Developer-v4.1.2.msi`" /qn TARGETDIR=`"C:\\Temp\\memurai_portable`"" -Wait`

3) 启动 Redis（前台/后台均可）：
- `Start-Process -FilePath "C:\\Temp\\memurai_portable\\Memurai\\memurai.exe" -ArgumentList @("--port","6379","--bind","127.0.0.1","--protected-mode","no","--appendonly","no") -WindowStyle Hidden`
- 验证：`& "C:\\Temp\\memurai_portable\\Memurai\\memurai-cli.exe" -p 6379 ping`（期望 `PONG`）

### 0.2 MySQL

脚本默认要求本机 `3306` 可用；如密码不在配置文件中，请先设置：
- `$env:IM_MYSQL_PASSWORD="<your_password>"`

---

## 1) 变更摘要（与本次测试相关）

### 1.1 两级回执开关与属性

- 位置：`src/main/java/com/miniim/gateway/config/WsSingleChatTwoPhaseProperties.java`
- 配置前缀：`im.gateway.ws.single-chat.two-phase.*`

### 1.2 单聊入口：写入 accepted 队列并回 ACK(accepted)

- 位置：`src/main/java/com/miniim/gateway/ws/WsSingleChatHandler.java`
- 行为：
  - `two-phase.enabled=true` 时：写入队列成功 → 立即回 `ACK(accepted)` 并返回（不再同步落库）
  - 写入队列失败：
    - `failOpen=true`：回退旧链路（同步落库 + ACK(saved)）
    - `failOpen=false`：返回 `ERROR reason=server_busy`

### 1.3 后台 Worker：投递与落库

- 位置：`src/main/java/com/miniim/gateway/ws/twophase/WsSingleChatTwoPhaseWorker.java`
- 模式：
  - `deliverBeforeSaved=false`：Save Worker 读 `acceptedStream` → DB 落库 → `ACK(saved)` → 再投递 `SINGLE_CHAT`
  - `deliverBeforeSaved=true`：Deliver Worker 读 `acceptedStream` → 先投递 `SINGLE_CHAT` → 写入 `toSaveStream`；Save Worker 再从 `toSaveStream` 落库并补 `ACK(saved)`

### 1.4 压测器新增 accepted/saved 延迟统计

- 位置：`scripts/ws-load-test/WsLoadTest.java`
- 输出：
  - `singleChat.acceptedMs`：sendTs→收到 `ACK(accepted)` 延迟分位数
  - `singleChat.savedMs`：sendTs→收到 `ACK(saved)` 延迟分位数（保持）

---

## 2) 性能回归（5000，open-loop，MsgIntervalMs=3000，60s×3）

### 2.1 基线（two-phase 关闭）

命令：
- `& scripts/ws-cluster-5x-test/run.ps1 -Instances 5 -OpenLoop -MsgIntervalMs 3000 -DurationSmallSeconds 60 -Repeats 3`

结果（3 次平均，见 `logs/ws-cluster-5x-test_20260115_160106/single_e2e_5000_avg.json`）：
- offered≈833.33 msg/s；delivered≈733.01 msg/s；deliver≈87.96%
- ackSaved≈97.57%；wsError≈0.27%
- E2E p50/p95/p99(ms)：414 / 764.67 / 948.67
- saved p50/p95/p99(ms)：489.67 / 784.67 / 1015.67（来源：`single_e2e_5000_r*.json` 统计）

### 2.2 two-phase（deliverBeforeSaved=false：先落库再投递）

命令：
- `& scripts/ws-cluster-5x-test/run.ps1 -Instances 5 -OpenLoop -MsgIntervalMs 3000 -DurationSmallSeconds 60 -Repeats 3 -SingleChatTwoPhaseEnabled -SingleChatTwoPhaseMode redis`

结果（3 次平均，来源：`logs/ws-cluster-5x-test_20260115_161539/single_e2e_5000_r*.json` 汇总）：
- offered≈833.33 msg/s；delivered≈63.03 msg/s；deliver≈7.56%
- ackAccepted≈100%；ackSaved≈7.56%；wsError≈1.65%
- accepted p50/p95/p99(ms)：36 / 113.67 / 167
- saved（=E2E）p50/p95/p99(ms)：27384 / 42394 / 44277

解读：
- 该模式把“对端可见”绑定到后台 Save Worker 的吞吐；当前实现为单线程消费/落库，无法在 833 msg/s 的持续负载下追平 DB 写入能力，导致队列堆积与 E2E 秒级→十几秒级→几十秒级放大。
- 结论：在现实现状下，`deliverBeforeSaved=false` **不适合开启**（会把“DB 排队”直接暴露为“用户可见延迟”）。

### 2.3 two-phase（deliverBeforeSaved=true：先投递再落库）

命令：
- `& scripts/ws-cluster-5x-test/run.ps1 -Instances 5 -OpenLoop -MsgIntervalMs 3000 -DurationSmallSeconds 60 -Repeats 3 -SingleChatTwoPhaseEnabled -SingleChatTwoPhaseDeliverBeforeSaved -SingleChatTwoPhaseMode redis`

结果（3 次平均，来源：`logs/ws-cluster-5x-test_20260115_162832/single_e2e_5000_r*.json` 汇总）：
- offered≈827.09 msg/s；delivered≈715.91 msg/s；deliver≈86.56%
- ackAccepted≈97.39%；ackSaved≈7.10%；wsError≈1.51%
- accepted p50/p95/p99(ms)：2.33 / 22.67 / 70.67
- E2E p50/p95/p99(ms)：775.67 / 1353.67 / 1474
- saved p50/p95/p99(ms)：27244 / 42951 / 44849

解读：
- `ACK(accepted)` 延迟显著低（毫秒级），但 `ACK(saved)` 在 60s 内严重滞后（落库吞吐不足，队列持续堆积）。
- 对端 E2E 在该负载下未优于基线（基线本身已是亚秒级），并出现一定退化（额外的 Streams hop/worker 调度开销 + 过载噪声）。

---

## 3) 连接规模评估（50k clients，单机限制显著）

本机压测端在 Windows 上实际只建立了约 `16k` 连接（见 connect 结果），该档用于暴露瓶颈与风险，不能作为“真实 50k 连接容量”结论。

### 3.1 基线（two-phase 关闭）

命令：
- `& scripts/ws-cluster-5x-test/run.ps1 -Instances 5 -OpenLoop -MsgIntervalMs 3000 -DurationSmallSeconds 15 -Repeats 1 -EnableLargeE2e -LargeE2eRepeats 1 -LargeE2eDurationSeconds 60`

连接（60s）：`logs/ws-cluster-5x-test_20260115_164405/connect_50000.json`
- connectOk=16266，connectFail=33734

单聊 E2E（60s×1）：`logs/ws-cluster-5x-test_20260115_164405/single_e2e_50000_r1.json`
- offered≈2567.92 msg/s；delivered≈1346.68 msg/s；deliver≈52.44%
- ackSaved≈60.32%；wsError≈6.59%
- E2E p50/p95/p99(ms)：5550 / 16856 / 18889

### 3.2 two-phase（deliverBeforeSaved=true）

命令：
- `& scripts/ws-cluster-5x-test/run.ps1 -Instances 5 -OpenLoop -MsgIntervalMs 3000 -DurationSmallSeconds 15 -Repeats 1 -EnableLargeE2e -LargeE2eRepeats 1 -LargeE2eDurationSeconds 60 -SingleChatTwoPhaseEnabled -SingleChatTwoPhaseDeliverBeforeSaved`

连接（60s）：`logs/ws-cluster-5x-test_20260115_165048/connect_50000.json`
- connectOk=16270，connectFail=33730

单聊 E2E（60s×1）：`logs/ws-cluster-5x-test_20260115_165048/single_e2e_50000_r1.json`
- offered≈2573.35 msg/s；delivered≈6 msg/s；deliver≈0.23%
- ackAccepted≈96.00%；ackSaved≈0.05%；wsError≈0.68%
- E2E p50/p95/p99(ms)：53837 / 54846 / 55040

解读：
- 在更高 offered 下，当前实现的 Deliver/Save Worker 吞吐成为决定性瓶颈，导致“几乎不投递 + E2E 极度堆积”。
- 结论：当前 two-phase 实现若要用于高并发场景，必须先做 worker 并行化/分片与 backlog 门禁，否则会把系统从“过载时错误率升高”变成“过载时长时间排队（用户体感更差）”。

---

## 4) 客观结论（本轮测试基于实测）

1) `ACK(accepted)` 的目标（降低发送确认延迟）已实现：5000 档位下 accepted p50 为毫秒级。
2) `ACK(saved)` 与 DB 落库吞吐在当前实现下不可接受：Save Worker 吞吐不足导致 saved 延迟与对端可见（在 deliverBeforeSaved=false 时）严重堆积。
3) 在“3s/条”的常规负载下，基线 E2E 已为亚秒级，two-phase 的“先投递后落库”在该负载下难以体现收益，反而引入额外复杂度与噪声。
4) 在更高 offered 场景（50k 档位的约 16k 实连），two-phase 当前实现会显著降低投递成功率并导致 E2E 爆炸，短期不建议开启。

---

## 5) 下一步建议（按 ROI 排序）

1) Worker 并行化（否则 two-phase 永远“排队型延迟”）：最小可落地方案是对 Streams 做 **按 `fromUserId` 分片（N 个 stream）**，每个分片单线程消费以保证发送者有序；N 可从 8/16/32 逐步压测校准。
2) 加 backlog 门禁：当 `XLEN/XPENDING` 超过阈值时拒绝 `accepted`（返回 `server_busy`），避免“系统已过载但仍持续接收导致延迟无限堆积”。
3) HA 语义补齐：实现 `XAUTOCLAIM`（或 pending reclaim）以保证 worker 异常/重启后 pending 能被重新消费，满足“可靠队列/可重放”的承诺。
4) 观测补齐：增加 Streams backlog、saveLag（savedAt-acceptedAt）分位数、deliver/save 失败计数；否则很难在生产判断“队列是否在追赶”。
5) 语义收敛：明确前端/产品语义（accepted≠saved）并在 UI 上做区分（例如发送中/已送达/已落库），否则会出现“accepted 很快但历史里查不到”的用户认知冲突。

