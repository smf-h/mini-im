# 变更提案: 单聊尾延迟治理（DB 队列 + 后置逻辑隔离）

## 需求背景

当前单聊链路的端到端（E2E）延迟呈现“秒级~十几秒级”长尾，静态+压测日志显示主要瓶颈更像是**排队**而非单次执行慢：

- `ws_perf single_chat` 的 `saveMsgMs/updateChatMs/ensureMembersMs` 多为毫秒级
- 但 `queueMs`（单连接串行队列等待）与 `dbQueueMs`（DB 线程池排队）成为主导
- `dbToEventLoopMs`（DB 完成→eventLoop 开始写回）已在上一轮拆分后明显收敛，说明“ACK 回切排队”已基本缓解

因此下一轮优化的主线是：**把 DB 线程池从“通用业务线程池”里解救出来**，并把过载从“不可控排队”改成“可控拒绝/降级”，以降低 P95/P99。

## 变更内容

1. **Post-DB 隔离（优先级最高）**
   - 单聊 DB 完成后的后置逻辑（route/Redis publish/跨实例 push 等）迁移到 `imPostDbExecutor`
   - DB 回调线程尽快释放，仅保留 ACK(saved) 的极轻快路径

2. **编码彻底离开 DB 回调线程（延迟/吞吐敏感）**
   - `WsWriter` 在非 eventLoop 调用时也走 `imWsEncodeExecutor`，避免 JSON 编码占用 DB 线程
   - eventLoop 只做 `writeAndFlush`

3. **可控过载（尾延迟优先）**
   - 通过有界队列+拒绝策略把 `dbQueueMs/postDbQueueMs/encodeQueueMs` 的无限增长变为 `server_busy`（你确认可接受 <5%）
   - 明确降级：push best-effort，ACK(saved) 优先

4. **验收口径与回归场景**
   - 复用 `scripts/ws-cluster-5x-test`、`scripts/ws-backpressure-multi-test`，形成可重复对照

## 影响范围

- **模块:** gateway / ws / cluster / executors
- **文件（预估）:**
  - `src/main/java/com/miniim/gateway/ws/WsSingleChatHandler.java`
  - `src/main/java/com/miniim/gateway/ws/WsWriter.java`
  - `src/main/java/com/miniim/config/ImExecutorsConfig.java`（如需调默认值）
  - `scripts/ws-cluster-5x-test/run.ps1`（补齐 encode 开关参数，便于回归）
  - `helloagents/wiki/modules/gateway.md`、`helloagents/wiki/testing.md`（同步文档）

## 核心场景

### 需求: 单聊尾延迟收敛
**模块:** gateway/ws
单机压测（5 实例网关）下，`clients=5000` 时单聊 E2E 的 P95/P99 需要显著收敛，同时维持 `server_busy/wsError` 在可接受范围内。

#### 场景: 高并发单聊（闭环 inflight）
- 50% 连接为 sender，持续发消息；inflight=4（客户端闭环）
- 观察：`ws_perf single_chat.queueMs/dbQueueMs/totalMs` 分位数 + `single_e2e_5000.json` 分位数

#### 场景: Redis 抖动 / publish 变慢
- route/跨实例 publish 变慢时，不得反向拖垮 DB 线程池并放大 `dbQueueMs`

#### 场景: 慢消费者（写出不可写）
- backpressure 触发时，系统应优先拒绝/降级而非堆积导致延迟爆炸与内存上涨

## 风险评估

- **风险:** push 迁移到 `post-db` 后，如队列满可能导致 push 丢弃/延迟
  - **缓解:** push 明确 best-effort；关键 ACK(saved) 仍走快路径；记录降级计数
- **风险:** encode 全量 offload 后引入新的排队点（encodeQueue）
  - **缓解:** 有界队列 + 拒绝策略 + 回归指标对照，避免“只是把排队从 A 挪到 B”
