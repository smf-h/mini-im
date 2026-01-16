# 任务清单: 单聊“两级回执”（ACK accepted / ACK saved）+ 异步落库

## 0. 前置确认（写在代码前，避免语义争议）
- [√] 0.1 明确 UI/产品语义：`ACK(accepted)` ≠ “已落库/可历史查询”；`ACK(saved)` 仍作为“已持久化/可历史查询”的标志
- [√] 0.2 明确对端可见策略：允许 `deliver-before-saved=true`（对端可能在落库前看到消息，最终一致）
- [√] 0.3 明确 Redis 不可用策略：默认 `fail-open=true`（回退旧链路）

## 1. 协议与开关
- [√] 1.1 增加配置属性类：`im.gateway.ws.single-chat.two-phase.*`（enabled/deliver-before-saved/fail-open/stream keys/group/batch/block 等）
- [√] 1.2 增加 WS 协议：新增 `ACK` 的 `ackType="accepted"`（不入库，仅 WS 回执）

## 2. 写入端改造（Producer）
- [√] 2.1 在 `src/main/java/com/miniim/gateway/ws/WsSingleChatHandler.java` 增加 two-phase 分支：
  - accepted：写入 Redis Streams（acceptedStream）成功后立即回 `ACK(accepted)`
  - 与现有幂等兼容：重复 clientMsgId 时返回同一个 serverMsgId 的回执
  - fail-open：Redis 不可用时回退旧链路或直接 `server_busy`

## 3. 异步投递/落库（Workers）
- [√] 3.1 新增 Deliver Worker：`XREADGROUP acceptedStream` → `wsPushService.pushToUser(toUserId, SINGLE_CHAT)` → `XADD toSaveStream` → `XACK acceptedStream`
- [√] 3.2 新增 Save Worker：`XREADGROUP toSaveStream` → DB 落库（捕获 DuplicateKey 视为成功）→ `wsPushService.pushToUser(fromUserId, ACK(saved))` → `XACK toSaveStream`
- [√] 3.3 线程模型：先单线程跑通（保证可控），必要时再做分片扩展（fromUserId hash 分片 stream）

## 4. 压测器与统计口径（必须能看见收益）
- [√] 4.1 `scripts/ws-load-test/WsLoadTest.java` 增加 accepted 统计：
  - `ackAccepted` 计数
  - `acceptedLatencyMs`（sendTs→收到 accepted 的延迟分位数）
  - `savedLatencyMs`（sendTs→收到 saved 的延迟分位数，保持现有）
- [√] 4.2 `scripts/ws-cluster-5x-test/run.ps1` 增加 two-phase 参数透传，支持一键对照：
  - baseline（two-phase off）
  - accepted-only（two-phase on, deliver-before-saved=false）
  - full（two-phase on, deliver-before-saved=true）

## 5. 验证与演练（可复现）

### 5.1 性能回归（5000）
- [√] 5.1.1 运行 baseline（对照当前 `ACK(saved)`）：
  - `scripts/ws-cluster-5x-test/run.ps1 -Instances 5 -OpenLoop -MsgIntervalMs 3000 -Repeats 3 ...`
- [√] 5.1.2 运行 accepted-only（看 accepted 分位数是否显著下降）：
  - `... -TwoPhaseEnabled -TwoPhaseDeliverBeforeSaved:$false ...`
- [√] 5.1.3 运行 full（看对端 E2E 是否显著下降）：
  - `... -TwoPhaseEnabled -TwoPhaseDeliverBeforeSaved ...`
- [√] 5.1.4 通过标准（建议）：
  - `accepted p99` 明显低于 `saved p99`
  - full 模式下 `E2E p99` 明显下降或在更高 offered 下仍收敛
  - `wsErrorRate < 5%`

### 5.2 连接规模评估（50000）
- [√] 5.2.1 说明限制：单机 Windows 压测端可能无法建立 50k 全量连接（本次实测 connectOk≈16k）；该档主要用于发现瓶颈与稳定性问题
- [√] 5.2.2 运行：`scripts/ws-cluster-5x-test/run.ps1 ... -EnableLargeE2e`
- [√] 5.2.3 记录：connectOk/connectFail、accepted/saved/E2E 分位数、wsError%

### 5.3 故障演练（最少 2 个即可先验证机制）
- [-] 5.3.1 DB 短暂不可用（停 MySQL 10s）：
  - 期望：accepted 仍可继续（若 Redis 可用）；save backlog 增大；DB 恢复后 backlog 被消费并补发 saved
- [-] 5.3.2 网关实例重启（发送中重启 1 个实例）：
  - 期望：accepted 不丢（消息在 stream）；重启后 workers 继续消费；可能出现重复投递（需要去重统计）

## 6. 记录与回退
- [√] 6.1 把压测命令与结果写入 `helloagents/wiki/`（包含 runDir、分位数、吞吐、错误率、stream backlog）
- [√] 6.2 回退验证：关闭开关后复测 baseline，确认无功能回归（baseline 已完成）
