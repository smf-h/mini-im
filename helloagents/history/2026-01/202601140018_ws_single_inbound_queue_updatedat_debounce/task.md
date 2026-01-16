# 任务清单: 单聊尾延迟收敛（入站队列上限 + 会话 updatedAt 去抖）

目录: `helloagents/plan/202601140018_ws_single_inbound_queue_updatedat_debounce/`

---

## 1. 入站队列上限（连接级门禁）
- [√] 1.1 核对 `WsSingleChatHandler`/`WsGroupChatHandler` 的入队逻辑与失败路径，确认 `server_busy` 对客户端可观测，验证 why.md#需求-压低单聊尾延迟（P95P99-收敛）-场景-过载时“可控失败”而非“无限排队”
- [√] 1.2 通过配置启用 `im.gateway.ws.inbound-queue.enabled` 并逐步调参 `max-pending-per-conn=500/2`，记录 `queueMs` 与 `wsErrorRate` 的变化，验证 why.md#需求-压低单聊尾延迟（P95P99-收敛）-场景-过载时“可控失败”而非“无限排队”

## 2. 会话 updatedAt 去抖（降低 DB 写放大）
- [√] 2.1 梳理单聊 DB 操作链路（getOrCreateSingleChatId/ensureMembers/save message/updateChat），明确哪些属于“必须写”，哪些属于“可最终一致”，验证 why.md#需求-会话列表置顶允许-1s-内最终一致-场景-高频单聊不再“每条消息都写会话表”
- [√] 2.2 实现 `t_single_chat.updated_at` 的 1s 去抖策略（推荐方案A：应用侧去抖），并确保不影响消息落库与 ACK(saved) 语义，验证 why.md#需求-会话列表置顶允许-1s-内最终一致-场景-高频单聊不再“每条消息都写会话表”

## 3. 安全检查
- [√] 3.1 执行安全检查（输入校验、敏感信息处理、权限控制、过载保护策略），确认无 EHRB 风险

## 4. 文档更新
- [-] 4.1 更新 `helloagents/wiki/modules/gateway.md`（本轮为避免历史文件编码风险，改为在独立回归报告中记录配置键与默认值）
- [√] 4.2 新增回归记录并补充本轮对照结果摘要（`helloagents/wiki/test_run_20260114_singlechat_inbound_queue_updatedat.md`）

## 5. 测试
- [√] 5.1 跑 5 实例回归（常规负载：`msgIntervalMs=3000`），输出 E2E、投递率、错误率，对照历史
- [√] 5.2 跑 burst 回归（高压：`msgIntervalMs=100`），验证去抖与门禁对 `queueMs/dbQueueMs` 的收敛效果

