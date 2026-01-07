# 模块: miniprogram

## 职责
- 提供微信小程序端（原生 + TypeScript）
- 复用现有后端能力：登录/鉴权、单聊/群聊、好友/群、朋友圈、通话信令

## 关键实现（以代码为准）
- 工程入口：`miniprogram/app.ts`、`miniprogram/app.json`
- 单页容器：`miniprogram/pages/index/index.*`
- HTTP 客户端：`miniprogram/services/http.ts`（`Result<T>` 解包、401/40100 自动 refresh 重试一次）
- WS 客户端：`miniprogram/services/ws.ts`（握手 `?token=` + `AUTH` 帧、重连退避、`token_expired` 处理）
- 登录态：`miniprogram/stores/auth.ts`（本地存储 `wx.setStorageSync`）

## 协议约定
- HTTP：`Authorization: Bearer <accessToken>`
- WS：`ws://.../ws?token=<accessToken>`，连接后发送 `type="AUTH"` 帧，收到 `AUTH_OK` 视为在线
- ID 精度：所有“语义为 ID”的字段在小程序侧按字符串处理

## 依赖
- 依赖后端服务：HTTP `:8080` + WS `:9001/ws`
- 依赖本地环境：MySQL/Redis（与后端一致）

