# 任务清单: 单聊尾延迟治理（序列化出 EventLoop + Post-DB 隔离）

目录: `helloagents/plan/202601131700_ws_single_encode_postdb_isolation/`

---

## 1. 写回链路（优先级 #1）
- [√] 1.1 在 `src/main/java/com/miniim/config/ImExecutorsConfig.java` 中新增 `imWsEncodeExecutor`（CPU 型、有界队列、可配置），验证 why.md#req-single-chat-tail-latency
- [√] 1.2 新增 WS encode 功能开关 `src/main/java/com/miniim/gateway/config/WsEncodeProperties.java`（默认关闭，避免回归），验证 why.md#req-single-chat-tail-latency，依赖任务1.1
- [√] 1.3 在 `src/main/java/com/miniim/gateway/ws/WsWriter.java` 中实现可选“两段式写回”（encode 可 offload；默认关闭），并保证 per-channel outbound 顺序，验证 why.md#scn-5x-5000-single-e2e，依赖任务1.2
- [√] 1.4 在 `src/main/java/com/miniim/gateway/ws/WsWriter.java` 中将背压门禁前置到调度前（不可写直接失败，不进入 executor/eventLoop 队列），验证 why.md#scn-slow-consumer-backpressure，依赖任务1.3

## 2. 后置链路隔离（优先级 #2）
- [√] 2.1 在 `src/main/java/com/miniim/config/ImExecutorsConfig.java` 中新增 `imPostDbExecutor`（有界队列），验证 why.md#req-single-chat-tail-latency
- [-] 2.2 在 `src/main/java/com/miniim/gateway/ws/WsSingleChatHandler.java` 中将“落库后后置逻辑（route/publish/push）”迁移到 post-db executor（ACK 快路径保持轻量），验证 why.md#scn-5x-5000-single-e2e，依赖任务2.1
  - 备注: 早期实测引入明显 E2E 回归（P50/P99 秒级上升），当前先保留 executor 基础设施与开关，待进一步拆分 push/ack 两条路径后再启用。

## 3. 安全检查
- [ ] 3.1 执行安全检查（按G9: 输入验证、敏感信息处理、权限控制、拒绝/降级 reason 明确、避免无界队列）

## 4. 文档更新
- [√] 4.1 更新 `helloagents/wiki/modules/gateway.md`（线程池拆分、背压门禁、可观测字段）
- [√] 4.2 更新 `helloagents/wiki/test_run_20260111.md`（新增对照实验记录模板：E2E 分位数 + 分段指标）

## 5. 测试
- [√] 5.1 运行 `scripts/ws-cluster-5x-test/run.ps1` 做 5x 分级对照，产出 logs 与 json 汇总（runDir 见 test_run 文档）
- [√] 5.2 运行 `scripts/ws-backpressure-multi-test/run.ps1` 重放慢消费者/单点登录踢人/群聊链路（runDir 见 test_run 文档）
