# 任务清单: 固定发送速率压测（open-loop）+ ACK 推进隔离（1s 最终一致）

目录: `helloagents/plan/202601131631_ws_openloop_ack_isolation/`

---

## 1. 压测 open-loop（固定速率）
- [√] 1.1 在 `scripts/ws-load-test/WsLoadTest.java` 增加 open-loop 发送模式（不以 inflight/ACK 节流），并输出 `attempted/sent/skippedHard/wsError` 等字段，用于 offered load 对照，验证 why.md#核心场景-场景-单聊-open-loop（5000-clients）
- [√] 1.2 在 `scripts/ws-load-test/run.ps1` 增加参数透传：`-OpenLoop/-MaxInflightHard`，验证 why.md#核心场景-场景-单聊-open-loop（5000-clients）
- [√] 1.3 在 `scripts/ws-cluster-5x-test/run.ps1` 的 single_e2e 步骤透传 open-loop 参数，确保一键回归可用，验证 why.md#核心场景-场景-单聊-open-loop（5000-clients）

## 2. ACK 推进隔离（线程池 + 1s 合并）
- [√] 2.1 新增 `src/main/java/com/miniim/config/ImAckExecutorProperties.java`，可配置 `im.executors.ack.*`，并在 `src/main/java/com/miniim/config/ImExecutorsConfig.java` 注册 `@Bean(\"imAckExecutor\")`
- [√] 2.2 在 `src/main/java/com/miniim/gateway/ws/WsAckHandler.java` 将 DB 推进逻辑从 `imDbExecutor` 迁移到 `imAckExecutor`，验证 why.md#核心场景-场景-高-ACK-压力（delivered/read）
- [√] 2.3 在 `src/main/java/com/miniim/gateway/ws/WsAckHandler.java` 增加 1s 合并/去抖（最大 msgId），并将 read/delivered 回执推送同节奏（可选），验证 why.md#核心场景-场景-高-ACK-压力（delivered/read）
- [ ] 2.4 增加 `ws_perf ack`（或等价可解析日志）以量化 ackQueueMs/ackDbMs/ackPushMs，便于判断隔离是否把瓶颈“从 dbQueueMs 挪到别处”，验证 how.md#3-可观测与回归

## 3. 安全检查
- [ ] 3.1 执行安全检查（按G9：输入验证、敏感信息处理、权限控制、EHRB风险规避）

## 4. 文档更新
- [ ] 4.1 更新 `helloagents/wiki/testing.md`：open-loop 参数、输出字段解释、对照口径
- [ ] 4.2 更新 `helloagents/wiki/modules/gateway.md`：ACK 隔离 executor 与 1s 最终一致说明
- [ ] 4.3 更新 `helloagents/CHANGELOG.md`：记录压测工具与 ACK 隔离变更

## 5. 测试与验收
- [√] 5.1 `mvn test` 通过（含新增/修改的单测）
- [√] 5.2 5 实例对照回归（open-loop）：`scripts/ws-cluster-5x-test/run.ps1 -Instances 5 -DurationSmallSeconds 60 -MsgIntervalMs 3000 -OpenLoop -MaxInflightHard 200`（必要时加压：`-MsgIntervalMs 1000`），对比 `ws_perf_summary_gw1.json` 与 `single_e2e_5000.json`
- [ ] 5.3 通过标准（建议）：在 attempted/sent 接近前提下，`single_chat.dbQueueMs` 与 `single_chat.queueMs` 的 P95/P99 明显下降；E2E P95/P99 不恶化；错误率（wsError+server_busy）<5%
