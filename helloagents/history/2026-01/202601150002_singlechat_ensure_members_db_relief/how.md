# 技术设计: 单聊主链路 DB 减负（ensureMembers 去热路径）

## 技术方案

### 目标与原则
- **目标:** 降低单聊发送主链路的 DB 负载与排队（`ws_perf single_chat.dbQueueMs`），从而压低 E2E 分位数；过载失败率控制 `<5%`。
- **原则:** 不大改架构；不改变“消息落库 + 推送”关键路径语义；对 delivered/read 游标采用“最终一致 + 可补偿”。

### 现状触发点（证据定位）
- **每消息确保成员存在（热点）**
  - `src/main/java/com/miniim/gateway/ws/WsSingleChatHandler.java`：调用 `singleChatMemberService.ensureMembers(singleChatId, fromUserId, toUserId)`
  - `src/main/java/com/miniim/domain/service/impl/SingleChatMemberServiceImpl.java`：`ensureMember()` 采用 `exists(limit 1)` → `save()`，两人=2 次 DB read/消息
- **member 行依赖的消费侧**
  - `src/main/java/com/miniim/gateway/ws/WsAckHandler.java`：调用 `singleChatMemberService.markDelivered/markRead`
  - `src/main/java/com/miniim/domain/service/impl/SingleChatMemberServiceImpl.java`：`markDelivered/markRead` 目前也会 `ensureMember()`（同样有 exists read）
- **已有补齐机制（可利用）**
  - `src/main/java/com/miniim/gateway/ws/WsResendService.java`：`ensureMembersForUser(userId)`
  - `src/main/java/com/miniim/domain/controller/SingleChatConversationController.java`：在会话列表查询前 `ensureMembersForUser(userId)`

### 推荐方案（最小入侵、收益可验证）

#### 1) 发送主链路移除 `ensureMembers()`
- 在 `WsSingleChatHandler` 的 DB 保存事务/闭包中，不再调用 `singleChatMemberService.ensureMembers(...)`。
- 理由：member 行不是“消息可送达”的必要条件；将其移出热路径能直接降低 DB executor 的必经工作量，减少 `dbQueueMs` 的排队源头。

#### 2) ACK 路径兜底补建（只在缺行时触发）
在 `SingleChatMemberServiceImpl.markDelivered/markRead`：
- 先执行 `update t_single_chat_member ... where single_chat_id=? and user_id=?`。
- 若更新结果为 0，再做一次 `exists(limit 1)` 区分：
  - 存在但无需更新（重复 ACK / 旧 msgId）→ 直接返回；
  - 不存在 → 调用 `ensureMember()` 补建后重试一次 update。
- 理由：避免“每次 ACK 都做 exists”，只在异常/缺行时补偿；在数据完整时几乎不增加额外 DB 操作。

#### 3) （可选增强）`ensureMember()` 幂等写优化
若后续观测到 `DuplicateKeyException` 成本显著，可将 `ensureMember()` 改为 Mapper 级别的 `INSERT ... ON DUPLICATE KEY UPDATE` 或 `INSERT IGNORE`（需显式写入 `id/join_at/created_at/updated_at`），以避免异常路径。

### 不做的事情（本轮刻意不做）
- 不引入 MQ/Streams，不改消息可靠投递协议。
- 不把 `pushToUser/redis publish` 挪出 E2E 关键路径（避免把“消息对端可见”延迟变差）。
- 不改表结构与索引（避免迁移风险）。

## 安全与性能
- **安全:** 不涉及鉴权与权限变更；不引入敏感数据落盘。
- **性能预期（定性）:** 单聊发送 DB 工作量减少（少 2 次 read/消息），在 DB 排队为主的场景下应能降低 `dbQueueMs` 与 E2E 尾延迟；ACK 路径仅在缺行时补偿。

## 测试与部署
- **对照测试:** 复用 `scripts/ws-cluster-5x-test/run.ps1` 进行 5 实例压测（open-loop），对比变更前后：
  - E2E：`p50/p95/p99`、`ackSavedRate`、`deliveredRate`、`wsErrorRate`
  - ws_perf：`single_chat.dbQueueMs`、`single_chat.totalMs`、`single_chat.ensureMembersMs`（应明显下降或消失）
- **回滚:** 通过开关恢复发送链路 `ensureMembers()`（如发现会话游标/列表异常或 DB QPS 异常）。

