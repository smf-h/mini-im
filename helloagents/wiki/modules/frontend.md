# 模块: frontend

## 职责
- 提供 Vue3 + TypeScript 的联调前端页面（登录/会话/聊天/好友申请）
- 通过 HTTP + WebSocket 与后端交互
- 提供站内通知（toast）与基础交互体验优化
- UI 风格：绿白主题（仿微信）

## 关键实现（以代码为准）
- 入口与路由：`frontend/src/router.ts`
- 登录与 token 存储：`frontend/src/stores/auth.ts`
- WebSocket 管理：`frontend/src/stores/ws.ts`
- 用户基础信息缓存：`frontend/src/stores/users.ts`
- 站内通知（toast）：`frontend/src/stores/notify.ts` + `frontend/src/views/AppShellView.vue`
- 单聊 UI：`frontend/src/views/ChatView.vue`
- 会话列表 UI：`frontend/src/views/ConversationsView.vue`
- 好友申请 UI：`frontend/src/views/FriendRequestsView.vue`

## 单聊 UI 约定（仿微信交互）
- 气泡大小随内容变化，并限制最大宽度
- 自己消息靠右、对方消息靠左
- 新消息到达/发送后自动滚动到底部；当用户上滑查看历史时不强制抢占滚动位置

## 会话列表约定（仿微信交互）
- 列表展示对方“昵称优先，其次 username，再次 id”
- 收到新消息时，会话置顶并更新预览（无需手动刷新）

## 站内通知（toast）约定
- 触发：
  - 收到 `SINGLE_CHAT` 且当前未打开对应聊天页
  - 收到 `FRIEND_REQUEST` 且当前不在好友申请页
- 内容：
  - 单聊 toast：标题为对方昵称/用户名，正文为消息摘要
  - 好友申请 toast：标题为“好友申请”，正文为“发起人 + 验证信息摘要”
- 交互：
  - 支持点击跳转（消息 → `/chat/:peerUserId`，好友申请 → `/friends`）
  - 默认 5s 自动消失；最多保留 5 条；按 key 去重（避免重复弹窗）
