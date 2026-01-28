# 任务清单: 单聊主链路 DB 减负（ensureMembers 去热路径）

## 1. 实现
- [√] 1.1 在 `src/main/java/com/miniim/gateway/ws/WsSingleChatHandler.java` 中移除单聊发送主链路的 `singleChatMemberService.ensureMembers(...)` 调用，并保持现有 `ws_perf single_chat` 打点可用
- [√] 1.2 在 `src/main/java/com/miniim/domain/service/impl/SingleChatMemberServiceImpl.java` 中调整 `markDelivered/markRead`：优先 update，缺行时再 `exists→ensure→retry`，避免每次 ACK 都做 `exists(limit 1)`
- [-] 1.3 （可选）在 `src/main/java/com/miniim/domain/mapper/SingleChatMemberMapper.java` 增加 `insertIgnore/upsert` 语句，并将 `ensureMember()` 从“查再插/异常兜底”升级为“幂等写无异常”，用于大量补建时的性能保护（本轮未观测到缺行补建成为瓶颈，暂不引入额外 SQL）

## 2. 安全检查
- [√] 2.1 检查变更不影响鉴权与权限逻辑；确认不会因为 member 缺行导致越权读取/写入（重点：会话列表、ACK 更新）

## 3. 验证与压测（可复现）
- [√] 3.1 使用 5 实例 open-loop 固定频率压测（`clients=5000`，`msgIntervalMs=1000/3000`，`60s × repeats=3`）对比变更前后 E2E 与 `ws_perf single_chat` 分段
- [√] 3.2 验证功能正确性：会话列表接口 `/single-chat/conversation/list` 与 `/cursor` 可正常返回、`myLastReadMsgId/peerLastReadMsgId` 可逐步补齐；Delivered/Read ACK 不出现持续 0 更新（已通过 `smoke_cluster_2x` 与单聊压测回归验证）
- [√] 3.3 记录结果到 `helloagents/wiki/`（包含命令、参数、runDir、分位数、错误率、ws_perf 分段变化）
