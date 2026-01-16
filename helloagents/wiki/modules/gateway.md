# 模块: gateway

## 职责
- 提供 Netty WebSocket 接入层
- 负责握手鉴权、连接会话管理与消息幂等
- 提供消息投递兜底能力（定时补发/离线标记）

## 关键实现（以代码为准）
- WebSocket 服务：`com.miniim.gateway.ws.NettyWsServer`
- 握手鉴权：`WsHandshakeAuthHandler`
- 帧处理：`WsFrameHandler`
- 通用写出：`com.miniim.gateway.ws.WsWriter`（统一序列化、`ERROR/ACK` 回包；支持 `writeAck(Channel, ...)`；保证写入在目标 channel eventLoop 执行；可选记录“写回进入 eventLoop 的排队延迟”用于性能量化）
- 慢消费者背压：`com.miniim.gateway.ws.WsBackpressureHandler` + `com.miniim.gateway.config.WsBackpressureProperties`（写缓冲水位 + unwritable 门禁 + 持续 unwritable 自动断开）
- 发送者顺序保障：`com.miniim.gateway.ws.WsChannelSerialQueue`（per-channel Future 链；保证同一连接的业务处理串行，避免“发送者乱序”）
- AUTH/REAUTH：`com.miniim.gateway.ws.WsAuthHandler`（认证与续期；AUTH 成功触发补发，单连接仅一次）
- PING/PONG：`com.miniim.gateway.ws.WsPingHandler`（客户端 JSON ping 回 JSON pong；writer-idle 发送 WS ping + JSON ping，并在心跳路径复验 `sessionVersion` 以保证“最终必失效”）
- 单聊消息：`com.miniim.gateway.ws.WsSingleChatHandler`（默认 `SINGLE_CHAT`：幂等 claim + DB 落库 ACK(saved) + best-effort 下发；DB 回调线程直接后置处理，写回由 `WsWriter` 回切，降低 eventLoop pending tasks）
- 单聊“两级回执”（实验性）：`com.miniim.gateway.ws.twophase.*`
  - 写入端：`WsSingleChatHandler` 在 `two-phase.enabled=true` 时可先回 `ACK(accepted)`（表示已进入可靠队列/日志），再异步补 `ACK(saved)`
  - Worker：`WsSingleChatTwoPhaseWorker`（Redis Streams：`acceptedStream/toSaveStream`；支持 `deliverBeforeSaved` 先投递后落库）
  - 注意：当前实现偏“功能验证/语义验证”，需要配合压测校准 worker 吞吐与 backlog 门禁，否则可能出现 `saved` 长时间滞后或 E2E 堆积（详见测试报告）
- 群聊消息：`com.miniim.gateway.ws.WsGroupChatHandler`（`GROUP_CHAT`：成员校验/禁言校验 + mention/reply 落库；下发由 `GroupChatDispatchService` 统一负责）
- 消息撤回：`com.miniim.gateway.ws.WsMessageRevokeHandler`（`MESSAGE_REVOKE`：仅发送者 + 2分钟窗口；成功后回 `ACK(revoked)` 并广播 `MESSAGE_REVOKED`）
- 群聊下发策略：`com.miniim.gateway.ws.GroupChatDispatchService`（策略1：推消息体；策略2：推 `GROUP_NOTIFY` 后由客户端走 HTTP `/group/message/since` 拉取；超大群/在线过多自动兜底为不推）
- 群成员ID集合缓存（群发加速）：`com.miniim.domain.cache.GroupMemberIdsCache`（Redis Set；key: `im:cache:group:member_ids:{groupId}`；miss 时回读 DB 并回填；成员变更主动失效 + TTL 兜底）
- 消息封装：`WsEnvelope`
- 补发服务：`com.miniim.gateway.ws.WsResendService`（离线补发 + 定时兜底补发共用）
- 通话信令：`com.miniim.gateway.ws.WsCallHandler`（`CALL_*`：invite/accept/reject/cancel/end/ice + timeout + 断线收尾）
- ACK 处理：`com.miniim.gateway.ws.WsAckHandler`（送达/已读 ACK 推进成员游标 + 回执给发送方）
- 好友申请：`com.miniim.gateway.ws.WsFriendRequestHandler`（WS 好友申请：幂等 claim + 落库 ACK(saved) + best-effort 推送）
- 站内/业务通知推送（best-effort）：`com.miniim.gateway.ws.WsPushService`
- 会话注册：`com.miniim.gateway.session.SessionRegistry`
- 多实例路由（Redis SSOT）：`com.miniim.gateway.session.WsRouteStore`（key: `im:gw:route:{userId}` -> `serverId|connId`；仅匹配当前连接才续期/删除；`serverId` 取自 `im.gateway.ws.instance-id`，缺省为 `{host}:{port}:{random8}`，建议生产显式配置）
- 多实例控制通道（Redis Pub/Sub）：
  - 发布：`com.miniim.gateway.ws.cluster.WsClusterBus`（topic: `im:gw:ctrl:{serverId}`）
  - 订阅：`com.miniim.gateway.ws.cluster.WsClusterListener`（处理 `KICK/PUSH`；PUSH 支持单用户与批量 `userIds[]`）
- 通话内存状态：`com.miniim.gateway.session.CallRegistry`（单聊 WebRTC 信令）
- 客户端消息 ID 幂等：`ClientMsgIdIdempotency`（本机 Caffeine + Redis `SETNX`；key: `im:idem:client_msg_id:{userId}:{biz}:{clientMsgId}`；TTL 默认 1800s）
- 定时任务：`com.miniim.common.cron.WsCron`（兜底补发调度器，内部调用 `WsResendService`）
- 业务线程池：`com.miniim.config.ImExecutorsConfig#imDbExecutor`（标记为 `@Primary`，避免与 Spring `taskScheduler` 的 `Executor` 注入冲突）
  - 可配置：`im.executors.db.core-pool-size/max-pool-size/queue-capacity`
- 可选 WS 编码线程池：`com.miniim.config.ImExecutorsConfig#imWsEncodeExecutor`
  - 可配置：`im.executors.ws-encode.core-pool-size/max-pool-size/queue-capacity`
  - 功能开关：`im.gateway.ws.encode.enabled=true`（默认关闭；用于把 JSON 序列化从 eventLoop 迁出，需结合压测验证是否收益为正）
  - 回归结论（2026-01-13，5实例/5000 clients）：在当前实现与参数下，开启该开关会导致单聊 E2E P50 明显变差，建议默认保持关闭，仅在补齐 encode 队列观测与参数校准后再开启对照
- 预留 post-db 线程池：`com.miniim.config.ImExecutorsConfig#imPostDbExecutor`
  - 可配置：`im.executors.post-db.core-pool-size/max-pool-size/queue-capacity`
  - 说明：当前仅提供基础设施，后续如启用“落库后后置逻辑隔离”需进一步拆分 ACK 快路径与 push 慢路径，避免引入新的排队瓶颈
- ACK 推进线程池：`com.miniim.config.ImExecutorsConfig#imAckExecutor`
  - 可配置：`im.executors.ack.core-pool-size/max-pool-size/queue-capacity`
  - 说明：用于承接 delivered/read 的“权限校验 + DB 推进 +（可选）回执推送”，避免占用 `imDbExecutor` 的队列槽位
- delivered/read ACK 1s 合并（最终一致）：`com.miniim.gateway.ws.WsAckHandler`
  - 开关：`im.gateway.ws.ack.batch-enabled`（默认 true）
  - 窗口：`im.gateway.ws.ack.batch-window-ms`（默认 1000）
  - 语义：对同一 `(ackUserId, ackType, chatType, chatId)` 在窗口内只推进最大 msgId；用于把“高频 ACK 推进”降为游标式推进，降低 DB 写放大
  - 取舍：会把 delivered/read 的可见性延迟推迟到窗口内（≤1s），但不影响消息落库 ACK(saved) 与消息本体下发（关键路径）

### 单聊“两级回执”（实验性，默认关闭）

配置前缀：`im.gateway.ws.single-chat.two-phase.*`

- `enabled`：是否启用两级回执（默认 false）
- `deliver-before-saved`：是否允许“先投递后落库”（默认 false；开启后对端可见延迟降低，但一致性变为最终一致）
- `fail-open`：Redis/队列不可用时的策略（默认 true：回退旧链路；false：拒绝并 `server_busy`）
- `mode`：`redis|local`（默认 `redis`；`local` 仅用于单实例/冒烟）
- `accepted-stream-key` / `to-save-stream-key` / `deliver-group` / `save-group` / `batch-size` / `block-ms` / `leader-lock-ttl-ms`：Streams/worker 参数（以代码默认值为准）

## 约定（v1）
- WsFrameHandler 尽量只做：协议解析/鉴权门禁/路由分发；AUTH/PING/通用写出与业务逻辑分别下沉到独立组件。
- WS 稳定性：WsFrameHandler 对入站帧做轻量限流（连接级 + 用户级）并对 `bad_json/missing_type` 等协议违规做阈值断连，避免被刷包拖垮网关。
- `CALL_*` 相关逻辑集中在 `WsCallHandler`：包含好友校验/占用/超时/落库/转发；WsFrameHandler 只负责分发。
- ACK 语义：发送方 ACK(SAVED) 代表落库成功；接收方 ACK_RECEIVED 代表已收到（用于推进消息状态）。
- WebRTC 单聊通话（Phase1）：gateway 仅负责 WS 信令（`CALL_*`）转发与通话状态占用（busy/timeout）管理，SDP/ICE 不应写入日志。
- 群聊禁言（发言限制）：在 WS `GROUP_CHAT` 入口落库前做强制校验（`t_group_member.speak_mute_until`），命中则返回 `ERROR reason=group_speak_muted`，不落库不投递。
- 内容风控（违禁词替换）：在 WS 消息入站（单聊/群聊/好友申请）落库与下发前对 `body` 做替换（命中则替换为 `***`），词库位于 `src/main/resources/forbidden-words.txt`。
- 多实例推送：所有“推给某个 userId 的 envelope”统一走 `WsPushService`；本机无 Channel 时会查 `WsRouteStore` 并通过 `WsClusterBus` 转发到目标实例。
- 单端登录（按 userId）与踢下线：WS 鉴权成功后会写入 `WsRouteStore` 并对旧路由触发 `KICK`；`connId` 比对用于避免误踢新连接。
- 会话即时失效（`sessionVersion`）：除握手/AUTH/REAUTH 校验外，连接存活期间对业务消息做“按连接限频复验”；同时在心跳（client ping / writer-idle）复验，避免 Pub/Sub 丢 KICK 时旧连接长期存活。
- 重连风暴缓解：离线补发 `WsResendService` 增加 `im:gw:lock:resend:{userId}` 分布式锁（短 TTL），窗口内最多触发一次补发（Redis 不可用时降级为 fail-open）。

配置文件：`src/main/resources/application.yml`

背压相关配置（默认启用；参数未配置时走代码默认值）：
- `im.gateway.ws.backpressure.write-buffer-low-water-mark-bytes`
- `im.gateway.ws.backpressure.write-buffer-high-water-mark-bytes`
- `im.gateway.ws.backpressure.close-unwritable-after-ms`
- `im.gateway.ws.backpressure.drop-when-unwritable`

性能分段打点（用于定位“排队 vs DB vs Redis/跨实例转发”）：
- 配置：`im.gateway.ws.perf-trace.enabled/sample-rate/slow-ms`
- 日志关键字（按 type）：
  - `ws_perf single_chat`：单聊处理链路（含 serial queue、dbQueue、dbToEventLoop、save/update、push）
  - `ws_perf group_chat`：群聊处理链路（含 serial queue、成员校验、member cache、save/update、dispatch）
  - `ws_perf group_dispatch`：群聊下发策略（routeStore.batchGet + fanout）
  - `ws_perf push`：推送路径（local push / routeStore.get / Redis Pub/Sub publish）
  - `ws_perf redis_pubsub`：Redis Pub/Sub publish 耗时（`WsClusterBus`）

入站串行队列保护（用于防止单连接“生产过快 → serial queue 无限排队”）：
- 配置：`im.gateway.ws.inbound-queue.enabled/max-pending-per-conn`
- 行为：启用后，当单连接待处理任务数超过阈值，会拒绝入队并返回 `ERROR reason=server_busy`（降低排队延迟爆炸）

离线补发（AUTH 后）控制：
- 配置：`im.gateway.ws.resend.after-auth-enabled`（默认 true）
- 用途：在压测/回归时可关闭，以避免历史离线补发污染 E2E/dup/reorder 统计（生产默认保持开启）
