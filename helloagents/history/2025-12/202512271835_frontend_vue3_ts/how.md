# 技术设计：前端（Vue3+TS）联调站点

## 技术方案
### 核心技术
- 构建：Vite
- 框架：Vue 3 + TypeScript
- 路由：Vue Router
- 状态：Pinia（集中管理 auth/ws 状态）
- HTTP：fetch 封装（避免引入重依赖；如需可后续切换 axios）

### 目录策略
- 按用户选择，将现有 `frontend/` 静态页替换为 Vue 项目。
- 保留后端仓库结构不变；前端仅作为一个子工程存在。

## 鉴权与会话
### Token 存储
- `localStorage`：
  - `accessToken`
  - `refreshToken`
  - `userId`
  - `accessTokenExpiresAt`（前端估算，用于提示/提前 refresh；最终以服务端 ERROR/token_expired 为准）

### HTTP 请求
- 统一在请求头携带：`Authorization: Bearer <accessToken>`。
- 当遇到 401 或明确 token 过期错误时，使用 `POST /auth/refresh`（refreshToken）换取新 accessToken 后重试一次。

### WebSocket 连接（浏览器约束）
- 连接：`ws://127.0.0.1:9001/ws?token=<accessToken>`（用于握手鉴权）
- 连接建立后立即发送：`{ "type": "AUTH", "token": "<accessToken>" }`
- token 过期处理：
  - 若收到 `ERROR` 且 `reason=token_expired` 或 socket close：触发 refresh → 重新连接 → 发送 `AUTH`（必要时可发送 `REAUTH`，本次优先统一用 `AUTH`）

## 业务实现要点
### 1) 登录
- 登录页：username/password 表单
- 调用 `/auth/login` 后落库 localStorage，并跳转到会话列表页

### 2) 单聊会话列表（cursor）
- API：`GET /single-chat/conversation/cursor?limit=20&lastUpdatedAt=...&lastId=...`
- 滚动加载：
  - 首次 lastUpdatedAt/lastId 为空
  - 下一页用上一页最后一条记录的 `updatedAt + singleChatId`

### 3) 聊天页（历史 + 实时）
- 历史：`GET /single-chat/message/cursor?peerUserId=...&limit=20&lastId=...`
- 发送：WS `SINGLE_CHAT`（生成 `clientMsgId`）
- 发送成功确认：收到 `ACK(saved)` 后将消息状态标记为“已发送/已保存”
- 接收：收到 `type="SINGLE_CHAT"` 推送后追加消息，并发送 `ACK(ack_receive)` 回执

### 4) 好友申请
- 发起：WS `FRIEND_REQUEST`（生成 `clientMsgId`）
- UI：收到 `ACK(saved)` 视为“已发送”
- 列表：HTTP `/friend/request/cursor?box=inbox|outbox|all&limit=20&lastId=...` 无限滚动
- 处理：`POST /friend/request/decide`：
  - `action="accept"`：返回 `singleChatId` → 可跳转聊天页
  - `action="reject"`：刷新 inbox 列表（或局部更新）

## 风险与规避
- 浏览器无法设置 WS 握手 Authorization header：已改用 query token。
- refreshToken 明文存储在 localStorage：仅用于本地联调站点；如未来要上线需切换为 HttpOnly Cookie 等方案。

## 验证
- 后端启动后：
  - 前端 `npm run dev` 能访问登录页并登录
  - WS 连接可建立，且能收到 `AUTH_OK`
  - 好友申请/会话列表/聊天收发可完整走通

