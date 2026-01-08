# Changelog

本文件记录项目所有重要变更。
格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 变更
- CI: 新增 GitHub Actions（Java 17 + Maven test）
- 分支保护: master 启用必需PR审核与状态检查
- WS: 送达/已读/补发从 `t_message.status` 迁移为“成员游标”模型（`t_single_chat_member` / `t_group_member`），可选兜底定时补发默认关闭（`im.cron.resend.enabled=true` 才启用）
- 幂等：`clientMsgId` 幂等 key 统一为 `im:idem:client_msg_id:{userId}:{biz}:{clientMsgId}`，TTL 默认调整为 1800s（本机缓存改为 `expireAfterWrite`）
- 群聊：新增“策略切换”（推消息体/通知后拉取/不推兜底），并默认按“群规模 + 在线人数”自动选择（可配置强制模式）
- 前端：扩展微信绿白视觉变量（颜色/阴影/圆角/分割线），新增 `Avatar/Badge/ListItem/Segmented` 微组件，并重构登录/会话/好友申请页为更接近微信的通栏列表与交互
- 前端：重构为桌面端 Sidebar Layout（Sidebar + 列表栏 + 主内容区），新增 `/chats`、`/contacts`、`/settings` 路由，并为旧路由提供重定向兼容
- 前端：新增全局 `+` 操作菜单（发起单聊/创建群聊/加入群组/添加朋友），并进一步打磨好友申请列表、群资料页、红点未读徽标与 toast 动效
- 前端：群聊输入框支持 `@` 选择群成员（插入 `@昵称` 并发送 `mentions` 列表以触发 important）

### 新增
- 网关：多实例路由（Redis SSOT）+ 跨实例控制通道（Redis Pub/Sub：`KICK/PUSH`），并支持按 `userId` 单端登录踢下线（带 `connId` 避免误踢）
- 鉴权：`sessionVersion`（JWT `sv` claim）用于 token 即时失效；新登录 bump `sv`，旧 token 重新鉴权将被拒绝
- 幂等：客户端 `clientMsgId` 幂等从本地 Caffeine 升级为 Redis `SETNX`（Redis 不可用时降级 Caffeine）
- WS：发送者消息不乱序（per-channel Future 链队列）
- 重连风暴缓解：离线补发增加跨实例短 TTL 锁，避免同一用户在短窗口内重复触发补发
- 缓存：Redis cache-aside（个人信息 / 群基本信息 / 单聊会话映射），并在关键变更点失效
- 缓存：好友ID集合（用于“好友可见性/时间线/朋友圈”等读优化），写路径主动失效 + TTL 兜底
- 缓存：群成员ID集合（Redis Set）用于群发加速（成员变更主动失效 + TTL 兜底）
- 朋友圈（MVP）：动态发布/删除、好友时间线、点赞/评论（后端 + 前端 `/moments`）
- 小程序：新增微信小程序端（`miniprogram/`，原生 + TypeScript，单页容器）
- 群聊：按实例分组批量 PUSH（批量路由 MGET + `userIds[]` 批量跨实例转发），支持 `important/normal` 分流
- 群聊：HTTP 增量拉取 `GET /group/message/since`（配合 `GROUP_NOTIFY` 使用）
- 前端：支持 `GROUP_NOTIFY` 后自动拉取增量并追加到当前群聊视图
- 通用能力：HTTP `@RateLimit`（AOP + Redis Lua），对关键写接口返回 429（含 `Retry-After`）
- 前端：收到 `session_invalid/invalid_token` 时清空 token 并回到登录页（避免无限重连）
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
- 群：新增成员禁言（发言限制），支持预设时长（10m/1h/1d/永久/解除），WS `GROUP_CHAT` 入口强制校验并返回 `ERROR group_speak_muted`
- 内容风控：新增违禁词替换（默认覆盖单聊/群聊/好友申请附言，命中替换为 `***`）
- 后端：接入 Flyway（`src/main/resources/db/migration/*`），启动时自动迁移；对已有库使用 `baseline-version=0`
- 配置：新增两份配置模板 `src/main/resources/application.env.yml`（仅变量）与 `src/main/resources/application.env.values.yml`（示例值）
- 消息：新增撤回（2分钟，仅发送者）：WS `MESSAGE_REVOKE` + 广播 `MESSAGE_REVOKED`，撤回后对外统一展示“已撤回”（HTTP/WS/离线补发一致）

### 修复
- WS：允许 `/ws?token=...`（query token）握手，修复浏览器端连接卡住导致 `auth_timeout`
- WS：连接存活期间对 `sessionVersion` 做按连接限频复验，并在心跳路径（client ping / writer-idle）检测失效连接，降低 Pub/Sub 丢 KICK 时“旧连接继续存活”的风险
- WS：开启 reader-idle 检测并在 writer-idle 发送 WS ping，降低僵尸连接导致的“假在线/TTL 续命”
- WS：入站帧增加轻量限流（连接级 + 用户级）与协议违规阈值断连，并显式限制 WS 文本帧最大 payload，降低刷包与超大 JSON 的风险
- WS：避免重复 `AUTH` 导致重复离线补发（同一连接仅补发一次），补齐补发失败日志上下文；`WsCron` 调度间隔配置对齐为 `im.cron.resend.fixed-delay-ms` 并兼容旧 `im.cron.scan-dropped.fixed-delay-ms`
- WS：补发逻辑抽离为 `WsResendService`，`WsFrameHandler/WsCron` 仅负责门禁与调度，减少重复代码
- WS：`CALL_*` 信令处理抽离为 `WsCallHandler`（占用/超时/落库/转发），进一步瘦身 `WsFrameHandler`
- WS：`ACK` 处理抽离为 `WsAckHandler`（送达/已读推进成员游标 + 回执给发送方）
- WS：`FRIEND_REQUEST` 处理抽离为 `WsFriendRequestHandler`（幂等 claim + 落库 ACK(saved) + best-effort 推送）
- WS：`SINGLE_CHAT/GROUP_CHAT` 处理分别抽离为 `WsSingleChatHandler` / `WsGroupChatHandler`，`WsFrameHandler` 仅做协议解析与分发
- WS：进一步拆分 `WsWriter/WsAuthHandler/WsPingHandler`，统一写出与认证/心跳逻辑，`WsFrameHandler` 收敛为路由器
- 前端：发送时若收到 `ERROR` 按 `clientMsgId` 展示失败原因
- 后端：所有语义为 ID 的 `long/Long` 字段统一序列化为字符串，避免前端 UID/ID 精度丢失
- Auth：修复 `/auth/login` 在 `passwordHash` 为空的历史用户场景下可能重复插入导致 500；补充异常日志与 Redis 不可用的明确错误信息
- 前端：单聊消息 UI 调整为“仿微信”（气泡宽度随内容、自/他消息左右分栏、新消息自动吸底）
- 前端：会话列表展示对方昵称/用户名，并在收到新消息时会话置顶+更新预览
- 前端：整体切换为“微信绿白风格”主题（顶栏/按钮/卡片/聊天气泡/列表）
- 前端：继续美化微信细节（聊天气泡尖角、列表动效、全局圆角/阴影/响应式）
- 前端：单聊消息气泡尖角下移，更贴近头像位置
- 前端：修复单聊通话记录弹窗模板标签缺失导致 `npm -C frontend run build` 失败
- 单聊：会话未读数 + 消息已读/未读展示（WS `ACK(read)` 推进游标并回执给发送方）
- 后端：`imDbExecutor` 标记为 `@Primary`，修复 Spring `Executor` 注入歧义（`imDbExecutor` vs `taskScheduler`）
- HTTP：未匹配路由/静态资源的请求返回 404（Result 封装），避免被兜底异常处理为 500
- 配置：`application.yml` 移除明文数据库密码，改为环境变量注入；新增 `application-dev.yml` 承载开发期 SQL stdout 与 debug logger
- 提交卫生：新增 `.gitattributes`，并在 `.gitignore` 中忽略小程序私有配置与模板目录，减少误提交风险
- 稳定性：对关键路径的吞异常补齐最小 debug 日志/注释，降低静默失败定位成本

## [0.0.1-SNAPSHOT] - 2025-12-25

### 新增
- 初始化 helloagents 知识库骨架与基础文档

