# 变更提案：前端（Vue3+TS）联调站点

## 背景
- 当前仓库已有后端 HTTP + Netty WebSocket（网关）能力，且已具备好友申请、单聊会话/消息查询等接口与 WS 协议。
- 现有 `frontend/` 为静态演示页面，不满足“面向客户”的登录/会话/聊天/好友申请完整交互。

## 目标
1. 提供一个可本地运行的 Vue3+TypeScript 前端站点，覆盖核心业务链路：登录 → WS 连接 → 单聊会话列表 → 聊天收发 → 好友申请（发起/列表/同意/拒绝）。
2. 协议兼容：浏览器端使用 `WS ?token=` 完成握手鉴权，同时在连接后发送 `AUTH` 帧（兼容后端与旧客户端逻辑）。
3. 体验口径：发送成功以收到 `ACK(saved)` 作为“已发送/已保存”的确认。

## 范围
### 范围内
- 前端工程：将 `frontend/` 替换为 Vite + Vue3 + TS 项目。
- 登录：`POST /auth/login`，并可刷新：`POST /auth/refresh`。
- 会话列表：`GET /single-chat/conversation/cursor` 无限滚动（按 `updatedAt` 倒序）。
- 聊天消息：HTTP 历史查询（cursor），WS 实时收发 `SINGLE_CHAT`，并发送 `ACK(ack_receive)`。
- 好友申请：
  - WS 发起：`FRIEND_REQUEST`（先落库，ACK(saved)，best-effort 推送给被申请方在线端）。
  - HTTP 列表：`/friend/request/cursor`（inbox/outbox/all）无限滚动。
  - 处理：`POST /friend/request/decide`（accept/reject），accept 返回 `singleChatId` 以便跳转会话。

### 范围外（本次不做）
- 生产级权限/安全加固（如 HttpOnly Cookie、CSRF、设备指纹等）。
- 用户资料/搜索用户/用户昵称头像等（当前后端未提供对应查询接口，前端以 userId 展示）。
- 复杂 UI 组件库接入与主题系统。

## 成功标准
- 本地启动后端（HTTP 8080 + WS 9001/ws）与 Redis/MySQL 后：
  - 前端可登录并持久化 token；
  - WS 可稳定连接并完成 `AUTH_OK`；
  - 好友申请：发起方收到 `ACK(saved)` 后显示“已发送”，被申请方在线可收到推送；HTTP inbox/outbox/all 可查询到记录；
  - 会话列表按 `updatedAt` 倒序滚动加载；
  - 单聊可收发消息，接收方发送 `ACK(ack_receive)`。

