# 变更提案: 单聊“两级回执”（ACK accepted / ACK saved）+ 异步落库

## 需求背景
当前单聊在“ACK(saved)=DB commit 成功才回”的语义下，端到端延迟的下界天然受 DB 事务与排队影响：即使优化 eventLoop、减少写放大，仍会被 `dbQueueMs` 牵制，导致 P95/P99 在高负载下容易抬升。

你希望进一步把“用户体感的即时性”压低，并能用压测验证收益；若收益不达预期，需要可以快速回退。

## 变更内容（概述）
引入两级回执语义，并通过“可靠日志/队列”把落库从关键路径异步化：
1. `ACK(accepted)`：表示**消息已写入可靠日志**（建议 Redis Streams），系统承诺“可恢复/可重放”。
2. `ACK(saved)`：表示**消息已完成 DB 落库**（与现有语义一致），用于最终一致与历史消息查询。

可选（同一套机制支持）：让“对端可见”在 `accepted` 后即可发生，而不必等待 DB 落库（由异步投递器先投递、后落库），从而显著降低 E2E 分位数。

## 成功标准（建议）
在同机 5 实例、open-loop 固定速率的可比场景下：
- `ACK(accepted)`：P99 显著低于当前 `ACK(saved)` 的 P99（目标量级：<200ms，具体取决于 Redis RTT）
- 单聊 E2E（对端可见）：p50/p95/p99 明显下降或在更高 offered load 下仍能收敛（以你当前脚本口径为准）
- 回退成本：通过配置开关可在 1 次重启内回到旧链路

## 影响范围
- **模块:** 网关 WS 单聊、异步投递/落库、压测器统计口径
- **文件（预估）:**
  - `src/main/java/com/miniim/gateway/ws/WsSingleChatHandler.java`
  - `src/main/java/com/miniim/gateway/ws/WsPushService.java`（复用，用于跨实例推送 ACK(saved)）
  - `src/main/java/com/miniim/gateway/ws/ClientMsgIdIdempotency.java`（复用，确保 accepted 幂等）
  - 新增：`src/main/java/com/miniim/gateway/ws/async/*`（Redis Streams 队列 + worker）
  - `scripts/ws-load-test/WsLoadTest.java`（新增 accepted 延迟统计）
  - `scripts/ws-cluster-5x-test/run.ps1`（新增对照参数）

## 核心风险与取舍
⚠️ 不确定因素: 现网/前端是否能接受“对端先看到消息，但 DB 落库稍后完成”的最终一致语义
- 假设: 允许“在线投递 best-effort + 落库后历史一致”，并通过 `ACK(saved)` 表达最终状态
- 决策: 方案默认做成**可配置分级开关**（先仅引入 accepted，不改变对端可见；确认收益后再开启“先投递后落库”）
- 备选: 若必须强一致（对端可见必须等落库），则两级回执只降低发送者 ACK 延迟，对端 E2E 改善有限

