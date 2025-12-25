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