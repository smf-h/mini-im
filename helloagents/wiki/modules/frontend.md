# 模块: frontend

## 职责
- 提供 Vue3 + TypeScript 的联调前端页面（登录/会话/聊天/好友申请）
- 通过 HTTP + WebSocket 与后端交互
- 提供站内通知（toast）与基础交互体验优化
- UI 风格：绿白主题（仿微信）

## 关键实现（以代码为准）
- 入口与路由：`frontend/src/router.ts`
- 全局布局（Sidebar Layout）：`frontend/src/components/AppLayout.vue`（Sidebar + 主内容区 + toast）
- 登录与 token 存储：`frontend/src/stores/auth.ts`
- WebSocket 管理：`frontend/src/stores/ws.ts`
- 用户基础信息缓存：`frontend/src/stores/users.ts`
- 站内通知（toast）：`frontend/src/stores/notify.ts` + `frontend/src/components/AppLayout.vue`
- 全局样式（视觉变量 SSOT）：`frontend/src/styles/app.css`
- 微组件：
  - `frontend/src/components/UiAvatar.vue`
  - `frontend/src/components/UiBadge.vue`
  - `frontend/src/components/UiListItem.vue`
  - `frontend/src/components/UiSegmented.vue`
- 全局操作菜单：`frontend/src/components/GlobalActionMenu.vue`（+ 菜单）
- 发起单聊弹窗：`frontend/src/components/StartChatModal.vue`
- Toast 容器：`frontend/src/components/UiToastContainer.vue`（TransitionGroup 动画 + 左侧色条 + 关闭按钮）
- 单聊视频通话（WebRTC，Phase1）：`frontend/src/stores/call.ts` + `frontend/src/components/CallOverlay.vue`（全局来电/通话面板）
- 单聊 UI：`frontend/src/views/ChatView.vue`
- 会话模块（双栏）：`frontend/src/views/ChatsView.vue`（左：最近会话；右：聊天窗口/空状态）
- 通讯录模块（双栏）：`frontend/src/views/ContactsView.vue`（左：导航+好友；右：新的朋友/群组/个人页）
- 好友申请 UI：`frontend/src/views/FriendRequestsView.vue`（入口：`/contacts/new-friends`）
- 群聊 UI：`frontend/src/views/GroupChatView.vue`（入口：`/chats/group/:groupId`）
- 消息撤回：`frontend/src/views/ChatView.vue` / `frontend/src/views/GroupChatView.vue`（WS `MESSAGE_REVOKE` + 推送 `MESSAGE_REVOKED`）
- 群组列表/创建/申请入群：`frontend/src/views/GroupsView.vue`（入口：`/contacts/groups`）
- 设置页：`frontend/src/views/SettingsView.vue`
- 个人主页 UI：`frontend/src/views/UserProfileView.vue`
- 群资料 UI：`frontend/src/views/GroupProfileView.vue`
- 朋友圈（MVP）：`frontend/src/views/MomentsView.vue`（入口：`/moments`；Sidebar 提供一级入口）

## 布局架构（桌面端 Sidebar Layout）
- 全局禁用 body 滚动（`body { overflow: hidden; }`），只允许在“列表栏/主内容区”内滚动
- 三段结构：
  - Sidebar（64px）：一级入口 `/chats`、`/contacts`、`/settings`
  - Sidebar（扩展）：新增 `/moments` 入口（朋友圈）
  - List Panel：模块内部的二级列表（会话列表/通讯录列表）
  - Main Stage：展示聊天窗口、好友资料、群资料等

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
- 消息撤回：我方消息在 2 分钟窗口内展示“撤回”入口；发送 WS `MESSAGE_REVOKE`，收到 `MESSAGE_REVOKED`/`ACK(revoked)` 后将目标消息内容更新为“已撤回”
- 通话入口：单聊页提供“视频通话”；通话面板为全局覆盖层（任意页面可接听/挂断）
- 通话记录：单聊页提供“通话记录”弹窗（基于 `/call/record/cursor`，前端按 peer 过滤展示）

## 会话列表约定（仿微信交互）
- 列表展示对方“昵称优先，其次 username，再次 id”
- 收到新消息时，会话置顶并更新预览（无需手动刷新）
- 点击进入会话会立即清零未读（前端先乐观清零，同时 best-effort 发送 WS `ACK(read)` 推进服务端游标）
- UI 形态：通栏列表（Edge-to-Edge），Hover 时整行浅灰背景
- 结构：左侧头像、中间昵称+预览、右侧时间+未读徽标（未读为 0 不显示）

## 头像与个人主页
- 头像统一使用 `UiAvatar`；在会话列表/好友申请/聊天消息里支持点击头像进入个人主页 `/u/:userId`
- 个人主页展示公开信息与 `FriendCode`；可在个人主页“一键申请好友”

说明：当前“个人主页”的主入口为 `/contacts/u/:userId`，并保留 `/u/:userId` 的重定向兼容。

## 加好友/加群入口
- 好友申请发送端改为输入 `FriendCode`（避免直接暴露/枚举 `uid`）
- 群聊采用“群码申请入群 + 审批”模型：群聊页提供“群资料”入口 `/group/:groupId/profile`，展示 `GroupCode` 与入群申请列表（owner/admin）

路由约定（新版）：
- 单聊：`/chats/dm/:peerUserId`
- 群聊：`/chats/group/:groupId`
- 群资料：`/chats/group/:groupId/profile`
- 好友申请：`/contacts/new-friends`
- 群组列表：`/contacts/groups`
- 全局 + 操作菜单：会话页/通讯录页左上角 `+`，包含「发起单聊 / 创建群聊 / 加入群组 / 添加朋友」
- 交互：点击 `+` 菜单外侧区域会自动关闭菜单

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
  - 支持点击跳转（单聊 → `/chats/dm/:peerUserId`，好友申请 → `/contacts/new-friends`）
  - 群聊 toast：跳转到 `/chats/group/:groupId`
  - 入群通知：跳转到 `/chats/group/:groupId/profile`
  - 默认 5s 自动消失；最多保留 5 条；按 key 去重（避免重复弹窗）

## 群聊 @ 成员选择
- 入口：`frontend/src/views/GroupChatView.vue` 输入框内输入 `@`
- 行为：
  - 弹出群成员选择列表（按昵称/用户名/uid 过滤）
  - 选择后插入 `@昵称` 到消息文本中
  - 发送时会把该成员的 `userId` 放入 `mentions`（WS `GROUP_CHAT.mentions`），用于服务端计算 `important=true`（@我/回复我）

## 大群通知（GROUP_NOTIFY）
- 背景：群规模/在线人数较大时，服务端可能不再下发消息体，而是下发 `type=GROUP_NOTIFY` 的轻量通知；客户端收到后走 HTTP 增量拉取补齐消息。
- 前端行为：
  - 群聊页：收到 `GROUP_NOTIFY` 且 `groupId` 匹配当前会话时，调用 `GET /group/message/since?groupId=...&sinceId=<本地最大serverMsgId>` 拉取增量并追加到消息列表
  - 非当前群聊页：默认不弹 toast（避免通知风暴）；如需提醒可仅对 `important=true` 做弱提示（后续可扩展）

## 会话免打扰（DND）
- 目标：按“会话维度”屏蔽站内通知（toast），不影响消息收发；不对对方产生任何可见通知或行为改变。
- 规则：
  - 单聊：开启后，来自该用户的普通消息不再弹 toast
  - 群聊：开启后，群内普通消息不再弹 toast；`important=true`（@我/回复我）仍允许 toast
- 前端实现：
  - store：`frontend/src/stores/dnd.ts`
  - localStorage key：`dnd:v1:${userId}`（按登录用户隔离）
  - 拦截点：`frontend/src/components/AppLayout.vue`（toast 触发前判断 DND）
  - UI：在单聊/群聊页 header 与会话列表提供开关与 🔕 标识
- 服务端同步（跨端一致）：
  - 登录后会调用 `GET /dnd/list` 拉取服务端配置覆盖本地缓存
  - 切换开关会调用 `POST /dnd/dm/set` / `POST /dnd/group/set` 写入服务端；失败会回滚到旧值

## 群聊禁言（发言限制）
- 目标：管理员对成员限制发言（禁止发送群消息），与“免打扰（仅屏蔽 toast）”语义严格分离。
- UI：
  - 群资料页：`frontend/src/views/GroupProfileView.vue` 成员菜单提供「禁言 10 分钟 / 1 小时 / 1 天 / 永久 / 解除」
  - 群聊页：`frontend/src/views/GroupChatView.vue` 在顶部提示禁言状态，并禁用输入框/发送按钮
- 依赖接口：
  - `POST /group/member/mute`
  - `GET /group/member/list`（用于展示 `speakMuteUntil`）
- WS 行为：
  - 被禁言用户发送 `GROUP_CHAT` 会收到 `ERROR reason=group_speak_muted`，前端提示并不落库消息

## 踢下线/会话失效（sessionVersion）
- 当账号在另一处登录导致会话失效时，服务端会返回 `AUTH_FAIL reason=session_invalid` 或 `ERROR reason=kicked/session_invalid` 并断开连接；前端会清空本地 token、关闭 WS，并跳转回登录页（避免“停留在旧页面但无权限”的困惑）。
