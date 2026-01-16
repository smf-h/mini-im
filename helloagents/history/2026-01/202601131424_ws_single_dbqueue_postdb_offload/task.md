# 任务清单: 单聊尾延迟治理（DB 队列 + Post-DB 隔离）

目录: `helloagents/plan/202601131424_ws_single_dbqueue_postdb_offload/`

---

## 1. 单聊 Post-DB 隔离（优先级最高）
- [-] 1.1 在 `src/main/java/com/miniim/gateway/ws/WsSingleChatHandler.java` 中，将 `wsPushService.pushToUser(...)` 从 DB 回调线程迁移到 `imPostDbExecutor` 执行；拒绝/异常时按 best-effort 降级并记录可观测事件，验证 why.md#核心场景-场景-高并发单聊（闭环-inflight）
- [√] 1.2 在 `src/main/java/com/miniim/gateway/ws/WsSingleChatHandler.java` 中，确保 `done.complete` 仍绑定在 ACK 写回链路（含 dbToEventLoop 量化）而非 push 完成，验证 why.md#核心场景-场景-高并发单聊（闭环-inflight）

## 2. WsWriter 编码 offload 收敛
- [√] 2.1 在 `src/main/java/com/miniim/gateway/ws/WsWriter.java` 中，开启 `im.gateway.ws.encode.enabled=true` 时，统一让非 eventLoop 调用也走 `imWsEncodeExecutor`（避免 DB 回调线程做 JSON 编码），验证 why.md#核心场景-场景-高并发单聊（闭环-inflight）
- [-] 2.2 在 `src/main/java/com/miniim/gateway/ws/WsWriter.java` 中，补齐 encode executor 拒绝的降级与日志（避免静默失败/难定位），验证 why.md#核心场景-场景-慢消费者（写出不可写）

## 3. 可控过载（队列与拒绝策略）
- [-] 3.1 在 `src/main/java/com/miniim/config/ImExecutorsConfig.java` 与 `src/main/java/com/miniim/config/ImDbExecutorProperties.java` 中，确认 DB 线程池队列为有界且拒绝可控；必要时调整默认队列容量以避免秒级排队，验证 why.md#核心场景-场景-高并发单聊（闭环-inflight）
- [√] 3.2 在 `scripts/ws-cluster-5x-test/run.ps1` 中补齐 `--im.gateway.ws.encode.enabled=true` 的可选参数（便于对照回归），验证 why.md#核心场景-场景-高并发单聊（闭环-inflight）

## 4. 安全检查
- [√] 4.1 执行安全检查（按G9: 输入验证、敏感信息处理、权限控制、EHRB风险规避）

## 5. 文档更新
- [ ] 5.1 更新 `helloagents/wiki/modules/gateway.md`（补齐单聊链路的线程隔离与配置键说明）
- [ ] 5.2 更新 `helloagents/wiki/testing.md`（补齐回归脚本参数与判定口径）

## 6. 测试
- [√] 6.1 运行 `scripts/ws-cluster-5x-test/run.ps1` 做对照回归（5实例，60s，MsgIntervalMs=100，Inflight=4）
- [-] 6.2 运行 `scripts/ws-backpressure-multi-test/run.ps1` 复现慢消费者（本轮聚焦单聊尾延迟链路；背压回归留待下一轮）

---

## 结果摘要（关键对照）

> 结论：在当前实现与当前机器环境下，`im.gateway.ws.encode.enabled=true` 会导致单聊 E2E 明显回归（P50 上升到秒级~十几秒级），建议默认保持关闭，仅在后续补齐 encodeQueue 可观测与参数校准后再尝试开启。

- 基线（encode 关闭）：`logs/ws-cluster-5x-test_20260113_152406/single_e2e_5000.json`，E2E `p50≈2997ms p95≈14846ms p99≈16649ms`
- 对照（encode 开启）：`logs/ws-cluster-5x-test_20260113_150909/single_e2e_5000.json`，E2E `p50≈11258ms p95≈15907ms p99≈16101ms`
