# 模块: gateway

## 职责
- 提供 Netty WebSocket 接入层
- 负责握手鉴权、连接会话管理与消息幂等
- 提供消息投递兜底能力（定时补发/离线标记）

## 关键实现（以代码为准）
- WebSocket 服务：`com.miniim.gateway.ws.NettyWsServer`
- 握手鉴权：`WsHandshakeAuthHandler`
- 帧处理：`WsFrameHandler`
- 通用写出：`com.miniim.gateway.ws.WsWriter`（统一序列化、`ERROR/ACK` 回包，并保证写入在目标 channel eventLoop 执行）
- AUTH/REAUTH：`com.miniim.gateway.ws.WsAuthHandler`（认证与续期；AUTH 成功触发补发，单连接仅一次）
- PING/PONG：`com.miniim.gateway.ws.WsPingHandler`（客户端 ping 回 pong；writer-idle 发送 JSON ping 并 touch TTL）
- 单聊消息：`com.miniim.gateway.ws.WsSingleChatHandler`（`SINGLE_CHAT`：幂等 claim + 落库 ACK(saved) + best-effort 下发）
- 群聊消息：`com.miniim.gateway.ws.WsGroupChatHandler`（`GROUP_CHAT`：成员校验/禁言校验 + mention/reply 落库 + best-effort 下发）
- 消息封装：`WsEnvelope`
- 补发服务：`com.miniim.gateway.ws.WsResendService`（离线补发 + 定时兜底补发共用）
- 通话信令：`com.miniim.gateway.ws.WsCallHandler`（`CALL_*`：invite/accept/reject/cancel/end/ice + timeout + 断线收尾）
- ACK 处理：`com.miniim.gateway.ws.WsAckHandler`（送达/已读 ACK 推进成员游标 + 回执给发送方）
- 好友申请：`com.miniim.gateway.ws.WsFriendRequestHandler`（WS 好友申请：幂等 claim + 落库 ACK(saved) + best-effort 推送）
- 站内/业务通知推送（best-effort）：`com.miniim.gateway.ws.WsPushService`
- 会话注册：`com.miniim.gateway.session.SessionRegistry`
- 通话内存状态：`com.miniim.gateway.session.CallRegistry`（单聊 WebRTC 信令）
- 客户端消息 ID 幂等：`ClientMsgIdIdempotency`（Caffeine 相关配置见 gateway/config）
- 定时任务：`com.miniim.common.cron.WsCron`（兜底补发调度器，内部调用 `WsResendService`）
- 业务线程池：`com.miniim.config.ImExecutorsConfig#imDbExecutor`（标记为 `@Primary`，避免与 Spring `taskScheduler` 的 `Executor` 注入冲突）

## 约定（v1）
- WsFrameHandler 尽量只做：协议解析/鉴权门禁/路由分发；AUTH/PING/通用写出与业务逻辑分别下沉到独立组件。
- `CALL_*` 相关逻辑集中在 `WsCallHandler`：包含好友校验/占用/超时/落库/转发；WsFrameHandler 只负责分发。
- ACK 语义：发送方 ACK(SAVED) 代表落库成功；接收方 ACK_RECEIVED 代表已收到（用于推进消息状态）。
- WebRTC 单聊通话（Phase1）：gateway 仅负责 WS 信令（`CALL_*`）转发与通话状态占用（busy/timeout）管理，SDP/ICE 不应写入日志。
- 群聊禁言（发言限制）：在 WS `GROUP_CHAT` 入口落库前做强制校验（`t_group_member.speak_mute_until`），命中则返回 `ERROR reason=group_speak_muted`，不落库不投递。
- 内容风控（违禁词替换）：在 WS 消息入站（单聊/群聊/好友申请）落库与下发前对 `body` 做替换（命中则替换为 `***`），词库位于 `src/main/resources/forbidden-words.txt`。

配置文件：`src/main/resources/application-gateway.yml`
