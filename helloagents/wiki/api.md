# API 手册

## HTTP API

### Auth
- `POST /auth/login`
- `POST /auth/refresh`
- `POST /auth/verify`

实现位置：`com.miniim.auth.web.AuthController`

---

## WebSocket

- 服务端入口：`com.miniim.gateway.ws.NettyWsServer`
- 握手鉴权：`com.miniim.gateway.ws.WsHandshakeAuthHandler`
- 帧处理：`com.miniim.gateway.ws.WsFrameHandler`
- 消息封装：`com.miniim.gateway.ws.WsEnvelope`

具体监听地址/路径等以 `src/main/resources/application-gateway.yml` 配置为准。
## WS 消息协议（单聊/ACK 摘要）

- SINGLE_CHAT 请求
  - 字段: { type:"SINGLE_CHAT", clientMsgId:string, to:long, msgType:"TEXT", body:string, ts:long }
  - 响应: 服务端先回 ACK { type:"ACK", ackType:"SAVED", clientMsgId, serverMsgId, ts }
  - 投递: 若对端在线，向对端下发 { type:"SINGLE_CHAT", from, to, clientMsgId, serverMsgId, msgType, body, ts }
  - 对端 ACK: { type:"ACK", ackType:"DELIVERED"|"READ", clientMsgId, serverMsgId, to:senderId }

- 幂等: key=(fromUserId + '-' + clientMsgId)，重复请求直接回已持久化的 serverMsgId。
- 状态枚举: t_message.status = 0 SENT, 1 SAVED, 2 DELIVERED, 3 READ, 4 REVOKED, 5 RECEIVED, 6 DROPPED。
