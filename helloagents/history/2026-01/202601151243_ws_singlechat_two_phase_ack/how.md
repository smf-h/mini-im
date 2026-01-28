# 技术设计: 单聊“两级回执”（ACK accepted / ACK saved）+ 异步落库

## 1. 设计目标
- 把“用户关键路径”从 DB commit 解耦：先快速确认 accepted，再后台落库并补发 saved。
- 可灰度、可回退：默认不改变对端可见语义；开启增强后才允许“先投递后落库”。
- 保持幂等：仍以 `clientMsgId` 幂等为准，避免重复写入/重复投递。

## 2. 现状证据（为什么瓶颈是 DB）
- 单聊分段日志：`ws_perf single_chat` 中 `dbQueueMs` 在高负载下主导 tail（你已多轮回归验证）
- 当前回执语义：`WsSingleChatHandler` 在 DB 保存完成后 `wsWriter.writeAck(... AckType.SAVED ...)`
- 幂等：`ClientMsgIdIdempotency` 使用 Redis `SETNX`（跨实例）+ Caffeine 降级

## 3. 分级开关（确保可回退）
新增配置（建议，命名可调整）：
- `im.gateway.ws.single-chat.two-phase.enabled`（默认 false）
- `im.gateway.ws.single-chat.two-phase.deliver-before-saved`（默认 false）
- `im.gateway.ws.single-chat.two-phase.fail-open`（默认 true：Redis 不可用则回退旧路径；false 则拒绝并返回 `ERROR server_busy`）
- Redis Streams：
  - `im.gateway.ws.single-chat.two-phase.stream.accepted`（默认 `im:stream:single_chat:accepted`）
  - `im.gateway.ws.single-chat.two-phase.stream.to_save`（默认 `im:stream:single_chat:to_save`）
  - `im.gateway.ws.single-chat.two-phase.group.deliver` / `group.save`（默认按 instanceId 派生）
  - `im.gateway.ws.single-chat.two-phase.batch-size` / `block-ms`

## 4. 核心链路（推荐：两段 Streams 解耦投递与落库）

### 4.1 写入端（WS 收到 SINGLE_CHAT）
在 `WsSingleChatHandler`：
1) 解析/鉴权/校验/违禁词过滤
2) 生成 `serverMsgId`（保持现有逻辑）
3) 幂等 claim：`ClientMsgIdIdempotency.putIfAbsent(idemKey, claim)`，若已存在则按现有返回（可直接回 `ACK(saved)` 或回 `ACK(accepted)`，由开关决定）
4) 若 `two-phase.enabled=true`：
   - 4.1) `XADD acceptedStream` 写入事件（字段建议见 4.3）
   - 4.2) 立即回 `ACK(accepted)` 给发送者（不等待 DB）
   - 4.3) 若 `deliver-before-saved=false`：仍走旧路径（DB 落库后投递与 saved 回执），用于“先观测 accepted 延迟收益”
   - 4.4) 若 `deliver-before-saved=true`：写入后**不直接推送**，交给异步投递器（见 4.2）
5) 若 Redis 不可用：
   - `fail-open=true`：回退旧路径（与当前一致）
   - `fail-open=false`：返回 `ERROR server_busy`（避免 accepted=不可靠）

### 4.2 异步投递器（Deliver Worker）
职责：从 accepted stream 拉取，投递给接收者，然后把消息“转移”到待落库 stream。

- 输入：`acceptedStream`
- 动作：
  1) `XREADGROUP deliverGroup` 读取
  2) 构造 `WsEnvelope(type=SINGLE_CHAT)` 并 `wsPushService.pushToUser(toUserId, envelope)`（跨实例可达）
  3) `XADD toSaveStream`（同 payload，可加 `deliveredAttemptTs`）
  4) `XACK acceptedStream`（若 crash：可能重复投递；需要客户端去重/服务端幂等）

设计理由：
- 投递器不等待 DB，因此对端可见延迟由“Redis RTT + 投递器调度 + WS 推送”决定，而不是 DB。

### 4.3 异步落库器（Save Worker）
职责：从 to_save stream 拉取，落库并补发 `ACK(saved)` 给发送者。

- 输入：`toSaveStream`
- 动作：
  1) `XREADGROUP saveGroup` 读取
  2) DB 落库：`singleChatService.getOrCreateSingleChatId` + `messageService.save(messageEntity)`（必要时捕获 DuplicateKey 视为已落库）
     - 说明：若你坚持“成员游标必须存在”，可在这里调用 `singleChatMemberService.ensureMembers(...)`（不在关键路径）
  3) 给发送者补发 `ACK(saved)`：构造 `WsEnvelope(type=ACK, ackType=saved, from=fromUserId, clientMsgId, serverMsgId)` 并 `wsPushService.pushToUser(fromUserId, ackEnv)`
  4) `XACK toSaveStream`

### 4.4 Streams 事件字段建议（最小集合）
字段建议全部用 String 存储（便于 `StringRedisTemplate.opsForStream()`）：
- `serverMsgId`
- `clientMsgId`
- `fromUserId`
- `toUserId`
- `msgType`
- `body`（或 `bodyJson`；注意大小上限）
- `sendTs`（用于 E2E 统计）
- `createdAtMs`（accepted 写入时刻）
- `producerServerId`（用于排障/归因）

## 5. 幂等与重复处理策略
- 写入端：仍用 `ClientMsgIdIdempotency` 做 accepted 级幂等（跨实例）
- 投递器：允许 at-least-once 投递（可能重复）；客户端/前端应按 `serverMsgId` 或 `clientMsgId` 去重（压测器已有 `seenClientMsgId` 去重统计）
- 落库器：以 `t_message.id`（msgId）为主键幂等；重复落库捕获 DuplicateKey 视为成功

## 6. 有序性影响（必须提前说明）
⚠️ 不确定因素: 多实例 + 多消费者并发下，服务端“严格按发送顺序投递”可能弱化
- 最保守做法：先让 deliver/save worker 以**单线程**运行（每类 1 个 consumer），优先验证延迟收益与正确性
- 工程化扩展：需要提升吞吐时，再做“按 fromUserId 分片的多 stream/多 group”（保证同一发送者落在同一分片，维持发送者有序）
- 验证方式：继续用压测器统计 `reorder/reorderByServerMsgId`，并新增 `reorderByFrom`（已有字段）对比开启前后变化

## 7. 可观测性（用于证明收益与排障）
建议新增日志/指标（最小集）：
- `ws_perf single_chat_accept`：`acceptedWriteMs / acceptedAckMs / streamLen / pending`
- Streams backlog：`XLEN`、`XPENDING`（deliver/save 两条 stream）
- `two_phase.saveLagMs = savedAt - acceptedAt` 分位数（反映 DB 追赶能力）
- 失败计数：`deliver_fail/publish_fail/save_fail/dup_db`

## 8. 回退策略（必须简单）
- 配置回退：关闭 `two-phase.enabled`（恢复当前同步落库 + ACK(saved) + 在线投递路径）
- 数据回收：Streams 中残留消息可保留（便于排障）或加 TTL/定期 trim（后续再做）

