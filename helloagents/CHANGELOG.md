# Changelog

本文件记录项目所有重要变更。
格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 变更
- CI: 新增 GitHub Actions（Java 17 + Maven test）
- 分支保护: master 启用必需PR审核与状态检查
- WS: 送达/已读/补发从 `t_message.status` 迁移为“成员游标”模型（`t_single_chat_member` / `t_group_member`），可选兜底定时补发默认关闭（`im.cron.resend.enabled=true` 才启用）

### 新增
- 单聊（WS）：SAVED 落库确认、ACK_RECEIVED 接收确认、定时补发与离线标记（实现细节以代码为准）
- 鉴权（WS）：新增 REAUTH（续期），允许在连接不断开的情况下刷新 accessToken 过期时间
- 好友申请（WS+HTTP）：新增 `FRIEND_REQUEST`（WS 落库 ACK(saved) + 在线 best-effort 推送）与 HTTP 列表接口（cursor/list）
- 联调前端：将 `frontend/` 替换为 Vue3+TypeScript 站点（登录/会话/聊天/好友申请）
- 前端：新增站内通知（收到 `SINGLE_CHAT` 时 toast 提醒，自动拉取发送方昵称/用户名；离线补发同样触发提醒）
- 前端：站内通知（toast）升级为可点击卡片，并增加 `FRIEND_REQUEST` 提醒（跳转到好友申请页）
- 后端：接入 Flyway（`src/main/resources/db/migration/*`），启动时自动迁移；对已有库使用 `baseline-version=0`

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

