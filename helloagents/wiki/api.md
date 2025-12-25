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
## 单聊 REST API（v1）

- POST /api/single-chat/send
  - 请求: { toUserId: long, content: string, clientMsgId: string }
  - 响应: { serverMsgId: string, status: SAVED|DELIVERED, ts: long }
  - 说明: 需要 Bearer Token；TEXT-only；如对端在线将尝试通过 WS 投递。

- GET /api/single-chat/history?peerId={id}&cursor={msgId?}&size={20}
  - 响应: { items: MessageDTO[], nextCursor: long?, hasMore: boolean }
  - 说明: 游标为消息 id；返回 id < cursor 的最近 size 条，按 id DESC。

- GET /api/single-chat/conversation?peerId={id}
  - 响应: { singleChatId: long }
  - 说明: 如不存在则自动创建。
