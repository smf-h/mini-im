# 变更提案: 单聊主链路 DB 减负（ensureMembers 去热路径）

## 需求背景
当前单聊在中高并发（尤其 open-loop / burst）下的主要瓶颈更像是 **DB executor 排队（dbQueueMs）**，而不是 Netty 写出背压本身。即使 `ws_perf single_chat.ensureMembersMs` 在单次采样里看起来不大，`ensureMembers()` 的“每消息 2 次 exists(limit 1)”会持续占用连接池与 DB CPU，放大 `dbQueueMs`，从而抬升 E2E 的 P50/P95/P99。

## 变更内容
1. **把 `ensureMembers()` 从单聊发送主链路移出**：发送消息不再强依赖 `t_single_chat_member` 的存在。
2. **保留一致性兜底**：在“需要更新 delivered/read 游标”的链路上，缺行时再补建（幂等），保证功能不丢。
3. **维持当前可观测性口径**：E2E 仍以“对端收到消息可见”为准；会话列表/游标等副作用允许最终一致（通过 API/重连补齐）。

## 影响范围
- **模块:** 网关 WebSocket 单聊、单聊成员游标服务
- **文件:**
  - `src/main/java/com/miniim/gateway/ws/WsSingleChatHandler.java`
  - `src/main/java/com/miniim/domain/service/impl/SingleChatMemberServiceImpl.java`
  - （可选）`src/main/java/com/miniim/domain/mapper/SingleChatMemberMapper.java`
- **数据:** 不改表结构（依赖 `t_single_chat_member` 的唯一键 `uk_single_chat_member(single_chat_id,user_id)`）

## 核心场景

### 需求: 单聊发送低延迟与高成功率
**模块:** `WsSingleChatHandler`
发送侧在高并发时减少 DB 必经操作，目标是降低 `dbQueueMs` 进而压低 E2E 的 P50/P95/P99，同时将过载失败控制在 `<5%`。

### 场景: delivered/read ACK 游标更新不丢
**模块:** `WsAckHandler` → `SingleChatMemberServiceImpl.markDelivered/markRead`
即使某些历史数据缺少 member 行，也能在 ACK 路径补建并完成更新（幂等），不要求强一致但要求可恢复。

## 风险评估
- **一致性变化（可接受的取舍）:** `t_single_chat_member` 从“发送强依赖”变为“游标/列表补齐 + ACK 兜底”，属于最终一致；风险主要在极端情况下短时间内会话列表/游标显示滞后。
- **兜底成本:** 若大量用户历史数据缺失，会在 ACK 或列表 API 时触发补建，需配合灰度/观察 DB QPS。
- **回滚策略:** 保留开关，必要时恢复发送链路的 `ensureMembers()`（但不建议长期依赖）。

