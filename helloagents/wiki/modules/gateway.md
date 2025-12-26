# 模块: gateway

## 职责
- 提供 Netty WebSocket 接入层
- 负责握手鉴权、连接会话管理与消息幂等
- 提供消息投递兜底能力（定时补发/离线标记）

## 关键实现（以代码为准）
- WebSocket 服务：`com.miniim.gateway.ws.NettyWsServer`
- 握手鉴权：`WsHandshakeAuthHandler`
- 帧处理：`WsFrameHandler`
- 消息封装：`WsEnvelope`
- 会话注册：`com.miniim.gateway.session.SessionRegistry`
- 客户端消息 ID 幂等：`ClientMsgIdIdempotency`（Caffeine 相关配置见 gateway/config）
- 定时任务：`com.miniim.common.cron.WsCron`（补发/离线标记兜底）

## 约定（v1）
- WsFrameHandler 尽量只做：协议解析/参数校验/鉴权门禁/调用业务方法；DB 操作与状态推进建议下沉到 domain/service（后续逐步重构）。
- ACK 语义：发送方 ACK(SAVED) 代表落库成功；接收方 ACK_RECEIVED 代表已收到（用于推进消息状态）。

配置文件：`src/main/resources/application-gateway.yml`
