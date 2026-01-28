# 任务清单：前端（Vue3+TS）联调站点

目录：`helloagents/plan/202512271835_frontend_vue3_ts/`

---

## 1. 前端工程初始化
- [√] 1.1 将 `frontend/` 替换为 Vite + Vue3 + TS 工程（路由/状态/基础页面骨架）
- [√] 1.2 更新仓库 `.gitignore`，忽略 `frontend/node_modules`、`frontend/dist`

## 2. 鉴权与基础设施
- [√] 2.1 实现 HTTP client（自动附带 Authorization；401 时 refresh 并重试一次）
- [√] 2.2 实现 Auth Store：登录/刷新/登出/持久化
- [√] 2.3 实现 WS client：`?token=` 握手 + `AUTH` 帧 + 基础重连（token_expired→refresh→reconnect）

## 3. 页面与业务
- [√] 3.1 登录页：`POST /auth/login`
- [√] 3.2 会话列表页：`/single-chat/conversation/cursor` 无限滚动（按 updatedAt 倒序）
- [√] 3.3 聊天页：历史 cursor + WS 发送/接收 + `ACK(ack_receive)` + `ACK(saved)` 显示“已发送”
- [√] 3.4 好友申请页：
  - WS 发起 `FRIEND_REQUEST`（ACK(saved) 显示“已发送”）
  - HTTP 列表：inbox/outbox/all cursor 无限滚动
  - 处理：`POST /friend/request/decide`（accept/reject）

## 4. 文档同步
- [√] 4.1 更新 `helloagents/wiki/api.md`（补充浏览器 WS 需用 query token；修正会话 cursor 参数 lastUpdatedAt/lastId 约束）
- [√] 4.2 更新 `helloagents/wiki/testing.md`（补充前端启动与联调步骤）
- [√] 4.3 更新 `helloagents/CHANGELOG.md`

## 5. 自测
- [√] 5.1 `mvn -DskipTests package`
- [√] 5.2 `scripts/ws-smoke-test`（auth + friend_request）
- [?] 5.3 前端本地联调走通：登录 → 好友申请 → 同意 → 会话列表出现 → 聊天收发（需人工打开浏览器验证）
