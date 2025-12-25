# 模块: gateway

## 职责
- 提供 Netty WebSocket 接入层
- 负责握手鉴权、连接会话管理与消息幂等

## 关键实现（以代码为准）
- WebSocket 服务：`com.miniim.gateway.ws.NettyWsServer`
- 握手鉴权：`WsHandshakeAuthHandler`
- 帧处理：`WsFrameHandler`
- 消息封装：`WsEnvelope`
- 会话注册：`com.miniim.gateway.session.SessionRegistry`
- 客户端消息 ID 幂等：`ClientMsgIdIdempotency`（Caffeine 相关配置见 gateway/config）

配置文件：`src/main/resources/application-gateway.yml`