# 技术设计: 单聊尾延迟治理（序列化出 EventLoop + Post-DB 隔离）

## 技术方案

### 核心技术
- Netty WebSocket（eventLoop 模型）
- Jackson（`ObjectMapper` JSON 编解码）
- `CompletableFuture` + Spring `ThreadPoolTaskExecutor`（线程池隔离）

### 设计原则（本次改造的“拆分边界”）

1. **eventLoop 只做 I/O + 极轻逻辑**：收包、最小分发、写包；避免在 eventLoop 里做 CPU 活（JSON 编解码/大循环/复杂路由）与任何可能阻塞的操作。
2. **DB 线程池只做 DB**：落库完成后尽快释放 DB 线程，不把 Redis publish/推送/路由决策塞到 DB 回调线程里。
3. **过载宁可拒绝也不堆积**：优先“尾延迟收敛”，接受一定比例 `server_busy`（你确认 <5%）；避免无界排队造成延迟爆炸与内存上涨。
4. **可观测先行**：每次拆分都要能回答“排队发生在哪一段”（encode / post-db / eventLoop / outbound 不可写）。

## 实现要点

### 1) `WsWriter` 两段式：Encode Offload + EventLoop Write

#### 目标
把 `WsWriter#doWrite` 中的 JSON 序列化与 `TextWebSocketFrame` 构造移出 eventLoop，使 eventLoop 只保留 `writeAndFlush`。

#### 核心做法
- **Stage A（encode executor）**：将 `WsEnvelope`/ACK/ERROR 等对象序列化为 JSON（建议优先用 `writeValueAsBytes`，避免中间 `String` 分配），并构造不可变的 WS frame 载荷（`TextWebSocketFrame` 或 `ByteBuf`）。
- **Stage B（eventLoop）**：仅执行 `channel.writeAndFlush(frame)`；并保持 `channel` 写顺序。

#### 顺序保证（强烈建议）
由于 encode 是异步的，必须在 writer 侧保证 per-channel outbound 顺序，否则会出现“encode 慢的先发被后发超车”的乱序。

建议的最小实现（不引入新组件）：
- 在 `WsWriter` 内部增加一个 **per-channel outbound 串行门禁**（基于 `channel.attr(AttributeKey)` 保存 tail future / pending counter），把一次写的“encode+write”作为一个串行单元：
  - 单元 A：在 encode executor 里完成 encode
  - 单元 B：在 eventLoop 里完成 write
  - 下一个单元必须等待上一个单元完成后才开始 encode（牺牲单连接并行，换取顺序正确；跨连接仍可并行）

#### 背压门禁前置（高 ROI）
在“调度之前”做快速门禁，避免慢端/不可写时继续堆积：
- `backpressure.enabled && dropWhenUnwritable && !channel.isWritable()` → 直接返回失败（不进入 encode executor，也不进入 eventLoop 队列）
- 在真正写出前（eventLoop）再二次检查：不可写则按策略降级（丢弃非关键/踢慢连接/限速）

#### 过载与拒绝策略
`encode executor` 采用有界队列与拒绝策略（例如：队列满直接拒绝 → `server_busy`），避免把 encode 队列变成“新的秒级排队源”。

### 2) `post-db executor`：隔离 DB 与后置逻辑

#### 目标
避免 DB 落库线程池承担 Redis publish / push / routeStore 等后置逻辑，提升 DB 池可用性与整体稳定性。

#### 推荐做法（延迟优先的折中）
在单聊落库完成回调处拆分两段：
1. **ACK(saved) 快路径**：DB 回调线程只做极轻处理与 ACK 调度（由 `WsWriter` 负责回切到 eventLoop 写出；encode 也已经 offload）
2. **push/publish 慢路径**：将路由、Redis publish、跨实例通知等放入 `post-db executor`

这样做的好处：
- ACK 延迟尽量不被额外线程切换放大
- DB 线程不会被 Redis 抖动拖住（吞吐与稳定性更好）

### 3) 分段打点与指标（用于量化根因）

建议新增或补齐以下指标（以你现有 `ws_perf` 日志格式为准）：
- `encodeQueueMs`：进入 encode executor 队列到开始 encode 的等待时间
- `encodeMs`：序列化 + 构帧耗时
- `postDbQueueMs`：进入 post-db executor 到开始执行的等待时间
- `eventLoopWriteQueueMs`：提交到 eventLoop 到真正执行 write 的排队时间（你已有类似 `dbToEventLoopMs` 的量化基础）
- `drop_unwritable` / `busy_reject` / `kick_slow` 计数：用于证明背压门禁生效

## 安全与性能

- **安全:** 不引入新外部依赖；不改变鉴权与权限判断；拒绝/降级必须有明确 reason，避免误伤后难以定位。
- **性能:** 通过“隔离 + 有界队列 + 拒绝策略”把不可控排队变成可控错误；以尾延迟为主指标做验收。

## 测试与部署

### Windows / PowerShell 可执行验证（复用现有脚本）

1. **基线**：5x 单聊 E2E（5000 clients）
2. **改造后对照**：同参数复跑（建议每轮切换 `UserBase`，避免补发/历史消息污染）
3. **慢消费者演练**：复用 `scripts/ws-backpressure-multi-test/run.ps1`
4. **故障演练（可选但推荐）**：滚动重启 1/5 网关实例 + 压测持续发送

### 通过/失败判定
以 `E2E P99` 为主指标，并同时满足：
- `wsError/server_busy` < 5%
- `encodeQueueMs/postDbQueueMs/eventLoopWriteQueueMs` 不出现持续线性增长（否则说明只是把瓶颈从 A 挪到 B）

