# 任务清单: 单聊主链路提速（ACK/投递更快，P50 回到 <1s 量级）

目录: `helloagents/plan/202601141742_ws_single_mainchain_speed/`

---

## 1. 单聊主链路（updated_at 异步化）
- [√] 1.1 在 `src/main/java/com/miniim/gateway/ws/WsSingleChatHandler.java` 中将 `t_single_chat.updated_at` 更新从 DB 主任务拆出，ACK(saved)/push 不再等待 UPDATE，验证 why.md#需求-单聊主链路提速（延迟优先）-场景-5-实例--open-loop（clients5000-msgintervalms3000）
- [√] 1.2 在 `src/main/java/com/miniim/gateway/ws/WsSingleChatHandler.java` 中把 UPDATE 提交到 `imPostDbExecutor` 并处理拒绝/异常（best-effort + 可观测），验证 why.md#需求-会话列表置顶允许-1s-最终一致-场景-单聊消息保存成功后会话列表短暂延迟置顶

## 2. eventLoop backlog 收敛（串行队列 inline）
- [√] 2.1 在 `src/main/java/com/miniim/gateway/ws/WsChannelSerialQueue.java` 中优化 `invokeOnEventLoop`：inEventLoop 时直接执行 supplier，验证 why.md#需求-单聊主链路提速（延迟优先）-场景-5-实例--open-loop（clients5000-msgintervalms3000）

## 3. 压测脚本 pinning 修正（5 实例均衡）
- [√] 3.1 在 `scripts/ws-load-test/WsLoadTest.java` 中修正 `rolePinned` 连接分布：按 `userId mod N` 选择 wsUrl，验证 why.md#需求-单聊主链路提速（延迟优先）-场景-5-实例--open-loop（clients5000-msgintervalms3000）

## 4. 安全检查
- [√] 4.1 执行安全检查（按G9: 输入验证、敏感信息处理、权限控制、EHRB风险规避）

## 5. 测试
- [√] 5.1 运行 `scripts/ws-cluster-5x-test/run.ps1`：open-loop（`clients=5000,msgIntervalMs=3000`）回归 3 次，输出 E2E/错误率与 `ws_perf_summary_gw1.json`
- [√] 5.2 运行 `scripts/ws-cluster-5x-test/run.ps1`：burst（`clients=5000,msgIntervalMs=100`）对照，验证队列不会雪崩

## 6. 文档更新
- [√] 6.1 新增回归报告 `helloagents/wiki/test_run_20260114_singlechat_mainchain_speed.md`（包含前后对照、offered load、pinning 变化说明）
- [√] 6.2 更新 `helloagents/CHANGELOG.md`（记录本轮主链路提速项与压测口径变化）
