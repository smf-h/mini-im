# 变更提案: 单聊尾延迟治理（序列化出 EventLoop + Post-DB 隔离）

## 需求背景

当前单聊在多实例压测下的 E2E P95/P99 仍有明显的秒级波动，且 `ws_perf single_chat` 的分段显示：整体更偏向“排队慢”主导，而不是 DB 单次执行耗时主导。

已完成的优化（历史变更见 `helloagents/history/2026-01/202601121939_ws_ack_eventloop_isolation/`）已经降低了 `dbToEventLoopMs/queueMs`，但尾延迟仍主要受“串行队列排队 + eventLoop backlog”影响。

进一步的证据点（需要以代码为准）：
- `WsWriter#doWrite` 在目标 `channel.eventLoop` 内执行 JSON 序列化并构造 `TextWebSocketFrame`，CPU 活会直接占用 eventLoop。
- `WsFrameHandler#channelRead0` 在 eventLoop 内执行 JSON 反序列化（入站解析）。

## 变更内容

1. **写回链路（优先级 #1）**：将 `WsWriter` 的 JSON 序列化/构帧从 eventLoop 迁出到专用 `encode executor`；eventLoop 仅保留 `writeAndFlush`。
2. **后置链路（优先级 #2）**：引入 `post-db executor` 隔离 DB 落库线程池与“落库后后置逻辑”（路由/Redis publish/push 等），避免把 DB 线程池当通用业务线程池使用。
3. **过载可控**：为 `encode executor`、`post-db executor` 设置有界队列与拒绝策略，把过载转换为可观测的 `server_busy`（你确认目标 <5%），避免无界排队导致秒级~十几秒尾延迟与内存爬升。
4. **可观测**：补齐关键分段指标与事件日志，支持量化“排队发生在哪里”（encode 队列 / post-db 队列 / eventLoop 回切 / outbound 不可写）。

## 影响范围

- **模块:** `gateway/ws`、`config`（线程池配置）
- **文件（计划）:**
  - `src/main/java/com/miniim/gateway/ws/WsWriter.java`
  - `src/main/java/com/miniim/gateway/ws/WsSingleChatHandler.java`
  - `src/main/java/com/miniim/config/ImExecutorsConfig.java`
  - `src/main/java/com/miniim/gateway/config/WsBackpressureProperties.java`
  - （可选）`src/main/java/com/miniim/gateway/ws/WsFrameHandler.java`（入站解析 offload，放入后续迭代评估）
- **协议兼容性:** 不改 WS 协议与字段；仅调整内部线程模型与过载策略。

## 核心场景

### 需求: 单聊尾延迟收敛
<a id="req-single-chat-tail-latency"></a>

目标：在不牺牲整体稳定性的前提下，优先压低 P95/P99（以你确认的“延迟”为主指标），并把错误控制在可接受范围（`server_busy` <5%）。

#### 场景: 5 实例 / 5000 clients / 单聊 E2E
<a id="scn-5x-5000-single-e2e"></a>

- **负载:** 5 实例网关；5000 clients；单聊发送；持续 180s（或沿用现有脚本默认）
- **通过标准（建议，待你最终拍板）:**
  - E2E `P99` 明显收敛（建议目标：`P99 < 3s`；若仍达不到，以“P99 降幅 + 分段收敛”作为阶段性验收）
  - `wsError/server_busy` 比例 `< 5%`
  - `ws_perf single_chat` 中 `queueMs`、`dbToEventLoopMs`、（新增）`encodeMs/encodeQueueMs` 有明确改善

#### 场景: 慢消费者 + 背压（30% 延迟读）
<a id="scn-slow-consumer-backpressure"></a>

- **负载:** 30% 客户端延迟读取（例如 5s）；其余正常；持续 60~180s
- **通过标准（建议）:**
  - 网关进程内存不再“线性爬升”
  - 普通收端 E2E `P99 < 5s`；慢端允许被断开/降级，但不能拖垮整体

## 风险评估

- **风险: 乱序/同会话有序性被破坏**
  - 原因：序列化从 eventLoop 移出后，若缺少 writer 侧的 per-channel 顺序门禁，可能发生“后完成的 encode 先写出”。
  - 缓解：writer 增加“单连接 outbound 串行门禁”（保证 `write(...)` 的相对顺序）；并在压测统计中显式量化乱序率。

- **风险: ByteBuf 引用计数/泄漏**
  - 原因：跨线程准备 `TextWebSocketFrame`/`ByteBuf` 后写出，失败路径若未释放会泄漏。
  - 缓解：由 `WsWriter` 统一管理 frame 的生命周期；失败/拒绝路径显式释放；测试环境可开启 leak detector。

- **风险: 线程池过大导致上下文切换与吞吐下降**
  - 缓解：线程数与队列长度配置化；默认按 CPU cores 取值；以“尾延迟优先”的思路宁可拒绝也不堆积。

