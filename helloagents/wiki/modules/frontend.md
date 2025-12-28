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
- 全局样式（视觉变量 SSOT）：`frontend/src/styles/app.css`
- 微组件：
  - `frontend/src/components/UiAvatar.vue`
  - `frontend/src/components/UiBadge.vue`
  - `frontend/src/components/UiListItem.vue`
  - `frontend/src/components/UiSegmented.vue`
- 单聊 UI：`frontend/src/views/ChatView.vue`
- 会话列表 UI：`frontend/src/views/ConversationsView.vue`
- 好友申请 UI：`frontend/src/views/FriendRequestsView.vue`
- 群聊 UI：`frontend/src/views/GroupsView.vue`、`frontend/src/views/GroupChatView.vue`
- 个人主页 UI：`frontend/src/views/UserProfileView.vue`
- 群资料 UI：`frontend/src/views/GroupProfileView.vue`

## 视觉语言系统（微信绿白）

设计变量集中在 `frontend/src/styles/app.css`，核心约定：
- `--primary`（微信绿）、`--bg`（全局背景）、`--surface/--panel/--card`（表面色）
- `--text/--text-2/--text-3`（主文案/次级/弱化）
- `--divider`（极淡分割线）、`--shadow-card`（弥散阴影）、`--shadow-float`（主按钮轻光晕）
- 兼容：保留 `--bg-soft/--shadow-soft/--radius-lg` 等旧变量名并映射到新变量，避免历史页面样式断裂

## 单聊 UI 约定（仿微信交互）
- 气泡大小随内容变化，并限制最大宽度
- 自己消息靠右、对方消息靠左
- 新消息到达/发送后自动滚动到底部；当用户上滑查看历史时不强制抢占滚动位置

## 会话列表约定（仿微信交互）
- 列表展示对方“昵称优先，其次 username，再次 id”
- 收到新消息时，会话置顶并更新预览（无需手动刷新）
- UI 形态：通栏列表（Edge-to-Edge），Hover 时整行浅灰背景
- 结构：左侧头像、中间昵称+预览、右侧时间+未读徽标（未读为 0 不显示）

## 头像与个人主页
- 头像统一使用 `UiAvatar`；在会话列表/好友申请/聊天消息里支持点击头像进入个人主页 `/u/:userId`
- 个人主页展示公开信息与 `FriendCode`；可在个人主页“一键申请好友”

## 加好友/加群入口
- 好友申请发送端改为输入 `FriendCode`（避免直接暴露/枚举 `uid`）
- 群聊采用“群码申请入群 + 审批”模型：群聊页提供“群资料”入口 `/group/:groupId/profile`，展示 `GroupCode` 与入群申请列表（owner/admin）

## 站内通知（toast）约定
- 触发：
  - 收到 `SINGLE_CHAT` 且当前未打开对应聊天页
  - 收到 `GROUP_CHAT` 且 `important=true` 且当前未打开对应群聊页
  - 收到 `FRIEND_REQUEST` 且当前不在好友申请页
  - 收到 `GROUP_JOIN_REQUEST` / `GROUP_JOIN_DECISION`（入群申请/审批结果）
- 内容：
  - 单聊 toast：标题为对方昵称/用户名，正文为消息摘要
  - 群聊 toast：标题为群名，正文为“发送方昵称: 消息摘要”（仅重要消息）
  - 好友申请 toast：标题为“好友申请”，正文为“发起人 + 验证信息摘要”
- 交互：
  - 支持点击跳转（消息 → `/chat/:peerUserId`，好友申请 → `/friends`）
  - 群聊 toast：跳转到 `/group/:groupId`
  - 入群通知：跳转到 `/group/:groupId/profile`
  - 默认 5s 自动消失；最多保留 5 条；按 key 去重（避免重复弹窗）
