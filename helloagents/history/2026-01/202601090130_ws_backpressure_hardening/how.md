# how - 方案设计（分层治理 + 最小改动优先）

## 分层策略

### L0：Netty 背压基础设施（已落地/可配置）

1) 设置 `WRITE_BUFFER_WATER_MARK`
- 让 `channel.isWritable()` 随出站缓冲水位变化而切换，产生 `channelWritabilityChanged` 事件。

2) 写出门禁：unwritable 时拒绝继续写入（drop）
- 目标：避免出站缓冲无限增长（核心止血点）。

3) 慢端踢出：持续 unwritable 超阈值后断开连接
- 目标：将“慢端成本”限定在该连接内，防止拖垮整体。
- 产生日志事件，便于验收与运维定位。

配置（Spring Boot relaxed binding）：
- `im.gateway.ws.backpressure.enabled`
- `im.gateway.ws.backpressure.write-buffer-low-water-mark-bytes`
- `im.gateway.ws.backpressure.write-buffer-high-water-mark-bytes`
- `im.gateway.ws.backpressure.close-unwritable-after-ms`（`<0` 禁用踢人）
- `im.gateway.ws.backpressure.drop-when-unwritable`

### L1：消息分级降级（建议下一步落地）

对“可以 best-effort 的推送”和“必须送达/必须提示”的消息做区分，避免一刀切：

- 关键类（不允许静默丢）：`ERROR/KICK/CALL_*` 等
  - 策略：遇到背压直接断开该连接（并记录事件），避免客户端一直处于假在线但收不到关键控制信令。
- 普通类（允许 best-effort）：`SINGLE_CHAT/GROUP_CHAT/GROUP_NOTIFY` 等
  - 策略：unwritable 时丢弃该连接上的推送（由可靠性链路兜底：离线补发/定时补发/拉取增量）。

实现建议：
- 在 `WsEnvelope` 或写出调用点引入 `priority/critical`（默认 normal）。
- `WsPushService.pushToChannel` 按 priority 选择“丢弃/断开”。

### L2：有界队列与限速（建议按 ROI 逐步增强）

如果后续仍存在“发送侧爆发 + 接收侧慢”的组合风险，可追加：

- per-connection/per-user 发送速率限制（令牌桶）
- group fanout 时的批次控制与延迟调度（避免瞬时把大量消息写入同一连接）
- 更完善的重连保护（指数退避、抖动、服务端连接洪峰限流）

## 验收与观测口径

### 观测字段（最小集合）

- `uid`：`SessionRegistry.ATTR_USER_ID`
- `connId`：`SessionRegistry.ATTR_CONN_ID`
- `bytesBeforeUnwritable/bytesBeforeWritable`：Netty 原生指标（可用于调水位）
- 事件：`ws backpressure: closing slow consumer channel`

### 回归用例（必须）

1) 慢消费者重放（单机）
- `powershell -ExecutionPolicy Bypass -File scripts/ws-load-test/run.ps1 -Mode single_e2e -WsUrls ws://127.0.0.1:9001/ws -Clients 200 -DurationSeconds 60 -MsgIntervalMs 100 -SlowConsumerPct 30 -SlowConsumerDelayMs 5000`
- 通过标准：
  - WorkingSet 不持续线性爬升
  - 普通收端 E2E P99 < 5s
  - 日志中出现踢慢端事件（并可统计触发次数/uid 分布）

2) 基础冒烟（不回归就不上线）
- `powershell -ExecutionPolicy Bypass -File scripts/ws-smoke-test/run.ps1 -Scenario basic`

## 风险与回滚

- 风险：背压门禁会导致“在线 best-effort 推送”丢失（对慢端尤甚）。
  - 规避：对关键消息走“断开 + 触发重连/拉取”，对普通消息依赖补发/增量拉取兜底。
- 回滚：将 `im.gateway.ws.backpressure.enabled=false`（或 `drop-when-unwritable=false`、`close-unwritable-after-ms=-1`）即可退回旧行为。

