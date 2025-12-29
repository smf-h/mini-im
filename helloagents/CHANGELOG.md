# Changelog

本文件记录项目所有重要变更。
格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 变更
- CI: 新增 GitHub Actions（Java 17 + Maven test）
- 分支保护: master 启用必需PR审核与状态检查
- WS: 送达/已读/补发从 `t_message.status` 迁移为“成员游标”模型（`t_single_chat_member` / `t_group_member`），可选兜底定时补发默认关闭（`im.cron.resend.enabled=true` 才启用）
- 前端：扩展微信绿白视觉变量（颜色/阴影/圆角/分割线），新增 `Avatar/Badge/ListItem/Segmented` 微组件，并重构登录/会话/好友申请页为更接近微信的通栏列表与交互
- 前端：重构为桌面端 Sidebar Layout（Sidebar + 列表栏 + 主内容区），新增 `/chats`、`/contacts`、`/settings` 路由，并为旧路由提供重定向兼容
- 前端：新增全局 `+` 操作菜单（发起单聊/创建群聊/加入群组/添加朋友），并进一步打磨好友申请列表、群资料页、红点未读徽标与 toast 动效
- 前端：群聊输入框支持 `@` 选择群成员（插入 `@昵称` 并发送 `mentions` 列表以触发 important）

### 新增
- 单聊（WS）：SAVED 落库确认、ACK_RECEIVED 接收确认、定时补发与离线标记（实现细节以代码为准）
- 单聊视频通话（WebRTC，Phase1）：WS `CALL_*` 信令（invite/accept/reject/cancel/end/ice/timeout）+ 通话记录 HTTP（`/call/record/cursor`、`/call/record/list`）
- 鉴权（WS）：新增 REAUTH（续期），允许在连接不断开的情况下刷新 accessToken 过期时间
- 好友申请（WS+HTTP）：新增 `FRIEND_REQUEST`（WS 落库 ACK(saved) + 在线 best-effort 推送）与 HTTP 列表接口（cursor/list）
- 联调前端：将 `frontend/` 替换为 Vue3+TypeScript 站点（登录/会话/聊天/好友申请）
- 前端：新增站内通知（收到 `SINGLE_CHAT` 时 toast 提醒，自动拉取发送方昵称/用户名；离线补发同样触发提醒）
- 前端：站内通知（toast）升级为可点击卡片，并增加 `FRIEND_REQUEST` 提醒（跳转到好友申请页）
- 会话免打扰（DND）：按会话开关屏蔽普通消息 toast（important/@我 不屏蔽），服务端持久化并在前端缓存（localStorage）用于跨端同步与兜底
- 群聊（WS+HTTP）：新增小群创建、群会话/群消息 cursor；WS 支持 `GROUP_CHAT`（落库 ACK(saved) + 在线投递）
- 群聊（重要消息）：新增 `t_message_mention` 稀疏索引表，仅对“@我/回复我”落库，用于离线补发与 `mentionUnreadCount`
- 前端：新增群列表/群聊页；收到 `GROUP_CHAT` 且 `important=true` 时 toast 提醒（不在群页时）
- 用户：新增公开个人主页（点击头像进入），展示 `FriendCode`；支持重置（限频）
- 好友：新增按 `FriendCode` 发送好友申请（替代直接输入 uid）
- 群：新增 `GroupCode` 与“申请入群→群主/管理员审批”流程，并提供群资料页与成员管理（踢人/设管理员/转让群主）
- 后端：接入 Flyway（`src/main/resources/db/migration/*`），启动时自动迁移；对已有库使用 `baseline-version=0`
- 配置：新增两份配置模板 `src/main/resources/application.env.yml`（仅变量）与 `src/main/resources/application.env.values.yml`（示例值）

### 修复
- WS：允许 `/ws?token=...`（query token）握手，修复浏览器端连接卡住导致 `auth_timeout`
- 前端：发送时若收到 `ERROR` 按 `clientMsgId` 展示失败原因
- 后端：所有语义为 ID 的 `long/Long` 字段统一序列化为字符串，避免前端 UID/ID 精度丢失
- Auth：修复 `/auth/login` 在 `passwordHash` 为空的历史用户场景下可能重复插入导致 500；补充异常日志与 Redis 不可用的明确错误信息
- 前端：单聊消息 UI 调整为“仿微信”（气泡宽度随内容、自/他消息左右分栏、新消息自动吸底）
- 前端：会话列表展示对方昵称/用户名，并在收到新消息时会话置顶+更新预览
- 前端：整体切换为“微信绿白风格”主题（顶栏/按钮/卡片/聊天气泡/列表）
- 前端：继续美化微信细节（聊天气泡尖角、列表动效、全局圆角/阴影/响应式）
- 单聊：会话未读数 + 消息已读/未读展示（WS `ACK(read)` 推进游标并回执给发送方）

## [0.0.1-SNAPSHOT] - 2025-12-25

### 新增
- 初始化 helloagents 知识库骨架与基础文档

