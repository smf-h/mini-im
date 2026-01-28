# Changelog

本文件记录项目所有重要变更。
格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 变更
- DB：启动时 Flyway `validate` 失败将自动执行 `repair()` 并重试迁移，避免因历史 FAILED/校验不一致导致应用无法启动（可通过 `im.flyway.auto-repair=false` 关闭）
- DB：`V8__msgseq_cursor.sql` 调整为幂等 DDL，可在“半完成迁移”场景下安全重跑；并移除窗口函数回填，避免大表迁移卡住启动
- 测试：新增 12h 全自动顺序压测脚本 `scripts/project-testkit/run-seq-12h.ps1`（拉起 2 实例 + 单聊连接/单聊消息/群聊消息阶梯顺序执行 + 产物归档与自动归因说明）
- 测试：新增压测文档模板 `scripts/project-testkit/templates/summary.md.tpl` 与 `scripts/project-testkit/templates/experiment.md.tpl`，用于自动生成符合 `project-testkit` 规范的 `report/summary.md` 与 `E**/report/experiment.md`
- 测试：新增压测守护脚本 `scripts/project-testkit/watchdog-12h.ps1`，用于 12h 内保持 runner 存活并避免重复 runner
- 测试：更新压测口径 `artifacts/project-testkit/20260125_213233/plan.md`，将顺序执行总时长从 24h 调整为 12h（每段 1h）
- WS：优化 ACK batch 聚合键，避免每条 ACK 创建字符串 key，降低高频路径分配（预期改善 p99；待跑测验证）
- 文档：补齐 WS 单端登录（Single-Session）口径，明确 `kicked/session_invalid` 的触发条件与客户端处理建议
- 文档：新增 WS 错误码总表（`AUTH_FAIL` vs `ERROR`、是否断连、前端统一处理策略）
- 文档：补齐补发/背压降级口径（群聊推消息体 vs 通知后拉取 vs 不推、WS backpressure 断连策略）
- 文档：补齐“有序性”口径（发送者有序 + 最终会话有序；实时推送 best-effort；客户端按 `msgSeq` 排序与 gap 补齐）
- 文档：对齐 WS/HTTP 接口契约口径（字段命名、游标语义、错误码与断连语义）到投递 SSOT
- WS：鉴权链路收敛为 AUTH-first（握手不鉴权、3s `auth_timeout`、未鉴权只允许 `AUTH/PING/PONG`、移除 URL query token）
- 方案：新增“WS 鉴权收敛为 AUTH-first（连接后首包鉴权）”方案包（用于后续一次性开发）
- DB/协议：引入 `msg_seq`（会话内单调序列）作为稳定排序/cursor 口径，HTTP 游标切换为 `lastSeq/sinceSeq`，WS 下发/ACK 回执携带 `msgSeq`
- 前端：好友申请“全部”方向展示改为图标 + 状态胶囊，整体更接近微信列表观感
- 前端：移除 uid/群号 等调试信息展示（设置页、发起单聊弹窗、群成员列表、侧边栏菜单）
- 前端：HTTP 鉴权失败（401/业务 40100）自动清理登录态并跳转登录页
- CI: 新增 GitHub Actions（Java 17 + Maven test）
- 分支保护: master 启用必需PR审核与状态检查
- 文档: 新增 WS 投递 SSOT（一页纸：DB vs Redis），明确 `t_message_ack` 弃用写入（仅保留表），并对齐 ACK/消息状态口径
- 文档: 补充根 `README.md`（本地启动/端口/验收故事线/FAQ），提升可交付与可演示性
- WS: 送达/已读/补发从 `t_message.status` 迁移为“成员游标”模型（`t_single_chat_member` / `t_group_member`），可选兜底定时补发默认关闭（`im.cron.resend.enabled=true` 才启用）
- 幂等：`clientMsgId` 幂等 key 统一为 `im:idem:client_msg_id:{userId}:{biz}:{clientMsgId}`，TTL 默认调整为 1800s（本机缓存改为 `expireAfterWrite`）
- 群聊：新增“策略切换”（推消息体/通知后拉取/不推兜底），并默认按“群规模 + 在线人数”自动选择（可配置强制模式）
- 前端：扩展微信绿白视觉变量（颜色/阴影/圆角/分割线），新增 `Avatar/Badge/ListItem/Segmented` 微组件，并重构登录/会话/好友申请页为更接近微信的通栏列表与交互
- 前端：重构为桌面端 Sidebar Layout（Sidebar + 列表栏 + 主内容区），新增 `/chats`、`/contacts`、`/settings` 路由，并为旧路由提供重定向兼容
- 前端：会话/通讯录列表新增搜索框；会话页免打扰图标由 emoji 替换为 SVG；单聊/群聊 header 操作改为 iconBtn；群聊新增成员 Drawer（仿微信 PC）
- 前端：新增全局 `+` 操作菜单（发起单聊/创建群聊/加入群组/添加朋友），并进一步打磨好友申请列表、群资料页、红点未读徽标与 toast 动效
- 前端：群聊输入框支持 `@` 选择群成员（插入 `@昵称` 并发送 `mentions` 列表以触发 important）

### 新增
- 网关：多实例路由（Redis SSOT）+ 跨实例控制通道（Redis Pub/Sub：`KICK/PUSH`），并支持按 `userId` 单端登录踢下线（带 `connId` 避免误踢）
- 网关：预留 post-db 线程池（`im.executors.post-db.*`），用于后续“后置隔离”优化
- 鉴权：`sessionVersion`（JWT `sv` claim）用于 token 即时失效；新登录 bump `sv`，旧 token 重新鉴权将被拒绝
- 幂等：客户端 `clientMsgId` 幂等从本地 Caffeine 升级为 Redis `SETNX`（Redis 不可用时降级 Caffeine）
- WS：发送者消息不乱序（per-channel Future 链队列）
- 重连风暴缓解：离线补发增加跨实例短 TTL 锁，避免同一用户在短窗口内重复触发补发
- 缓存：Redis cache-aside（个人信息 / 群基本信息 / 单聊会话映射），并在关键变更点失效
- 缓存：好友ID集合（用于“好友可见性/时间线/朋友圈”等读优化），写路径主动失效 + TTL 兜底
- 缓存：群成员ID集合（Redis Set）用于群发加速（成员变更主动失效 + TTL 兜底）
- 朋友圈（MVP）：动态发布/删除、好友时间线、点赞/评论（后端 + 前端 `/moments`）
- 群聊：按实例分组批量 PUSH（批量路由 MGET + `userIds[]` 批量跨实例转发），支持 `important/normal` 分流
- 群聊：HTTP 增量拉取 `GET /group/message/since`（配合 `GROUP_NOTIFY` 使用）
- 前端：支持 `GROUP_NOTIFY` 后自动拉取增量并追加到当前群聊视图
- 通用能力：HTTP `@RateLimit`（AOP + Redis Lua），对关键写接口返回 429（含 `Retry-After`）
- 前端：收到 `session_invalid/invalid_token` 时清空 token 并回到登录页（避免无限重连）
- 单聊（WS）：SAVED 落库确认、`ACK(delivered/read/ack_receive/ack_read)` 推进成员游标（cursor），并支持离线补发/兜底补发（实现细节以代码为准）
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
- 测试：补充 WS 压测脚本（k6 方案 + Java 备用方案），并在知识库记录单机快测结论
- 性能：性能排查建议优先结合压测脚本的端到端统计与应用日志逐步定位瓶颈（DB/Redis/网关背压等）
- 测试：新增 5 实例一键分级压测 `scripts/ws-cluster-5x-test/run.ps1`（默认 500/5000/50000；可选 `-Run500k`）
- 测试：新增群聊 push 压测 `scripts/ws-group-load-test/run.ps1`（含重复/乱序统计）
- 工具：Codex 全局完成弹窗（Windows）：`C:/Users/yejunbo/.codex/config.toml` notify hook + `C:/Users/yejunbo/.codex/notify.py`

### 修复
- WS：增加写缓冲水位 + 慢消费者背压保护（连接 unwritable 时拒绝继续写入，持续 unwritable 自动断开），避免慢端拖垮整体并提升可观测性
- WS：生产侧写路径补齐“调度前门禁”（避免 eventLoop pending tasks 堆积）；补发在不可写连接上跳过；critical push 在不可写时断开连接
- WS：单聊 ACK 写回“回切隔离”（DB 回调线程直接后置处理，写回由 `WsWriter` marshal 到目标 channel eventLoop），用于降低 `dbToEventLoopMs` 与 eventLoop backlog
- WS：单聊主链路提速：`t_single_chat.updated_at` 更新异步化（`imPostDbExecutor` + 1s 去抖），ACK(saved)/在线投递不再等待 UPDATE，用于降低 `dbQueueMs/queueMs` 并压低 E2E 分位数
- WS：`WsChannelSerialQueue` 在 inEventLoop 时直接执行（不再额外 `eventLoop().execute(...)`），减少 eventLoop pending tasks 与调度开销
- WS：精简网关开关/可选能力（移除未使用的 perf-trace / inbound-queue / ws-encode 相关配置与脚本透传）
- 测试：`ws-load-test` 的 `rolePinned` 策略改为按 `userId mod N` 均衡分配 `wsUrls`（5 实例不再只打到 2 个实例，避免热点导致的误判）
- 测试：`scripts/ws-cluster-5x-test/run.ps1` 修复多实例启动参数截断/端口探测不稳定；新增 Hikari 参数透传与默认 `AutoUserBase`（避免 Redis `clientMsgId` 幂等污染导致“ACK(saved) 但不投递”的假象）
- 测试：`ws-load-test` 新增发送模型 `sendModel=spread|burst`（摊平发送 vs 齐发微突发），并在 `ws-cluster-5x-test` 透传为 `LoadSendModel`
- 稳定性：单机多 JVM 多实例压测时为每实例初始化唯一 Snowflake workerId（`im.id.worker-id`），避免 MyBatis-Plus `ASSIGN_ID` 产生 `DuplicateKeyException` → `ERROR internal_error`
- 性能/稳定性：`imDbExecutor` 采用 `AbortPolicy`，单聊入口捕获 `RejectedExecutionException` 并映射为 `ERROR server_busy`，避免过载时排队雪崩
- 测试：Windows 下启动脚本优先使用 `JAVA_HOME\\bin\\java.exe`（避免 `Oracle\\Java\\javapath\\java.exe` shim 导致残留进程/内存采样错误）；`ws-backpressure-multi-test` 增加 `meta_*.json` 与 mem CSV pid 字段，便于复现与排查
- 构建：修复个别源码文件 BOM 导致 `mvn package` 编译失败（Windows 默认编码下报 `\ufeff`）
- WS：允许 `/ws?token=...`（query token）握手，修复浏览器端连接卡住导致 `auth_timeout`
- WS：连接存活期间对 `sessionVersion` 做按连接限频复验，并在心跳路径（client ping / writer-idle）检测失效连接，降低 Pub/Sub 丢 KICK 时“旧连接继续存活”的风险
- WS：开启 reader-idle 检测并在 writer-idle 发送 WS ping，降低僵尸连接导致的“假在线/TTL 续命”
- WS：入站帧增加轻量限流（连接级 + 用户级）与协议违规阈值断连，并显式限制 WS 文本帧最大 payload，降低刷包与超大 JSON 的风险
- 稳定性/性能：Redis down 时群成员缓存（`GroupMemberIdsCache`）增加 fail-fast + 本机兜底，避免群聊链路被 Redis 超时放大为秒级尾延迟
- 稳定性/性能：`RedisJsonCache` 增加 fail-fast，Redis 不可用时避免 cache 读写反复超时造成雪崩
- 性能：群聊本机 fanout 复用序列化结果（prepare once, write many），并补齐 `ByteBuf` 释放保护（降低 CPU/GC 上界）
- 测试：`scripts/ws-cluster-5x-test/run.ps1` 在 `-SkipRedisCheck`（常用于 Redis down 专测）时默认跳过 cluster smoke，避免误报
- WS：避免重复 `AUTH` 导致重复离线补发（同一连接仅补发一次），补齐补发失败日志上下文；`WsCron` 调度间隔配置对齐为 `im.cron.resend.fixed-delay-ms` 并兼容旧 `im.cron.scan-dropped.fixed-delay-ms`
- WS：补发逻辑抽离为 `WsResendService`，`WsFrameHandler/WsCron` 仅负责门禁与调度，减少重复代码
- WS：`CALL_*` 信令处理抽离为 `WsCallHandler`（占用/超时/落库/转发），进一步瘦身 `WsFrameHandler`
- WS：`ACK` 处理抽离为 `WsAckHandler`（送达/已读推进成员游标 + 回执给发送方）
- 测试：Java WS 压测器新增 open-loop（固定速率）模式与 `maxInflightHard/maxValidE2eMs` 自保门禁，输出 `attempted/sent/skippedHard/e2eInvalid` 用于 offered load 对照
- 测试：5实例一键分级压测 `ws-cluster-5x-test` 增加 open-loop 参数透传，并支持 `im.gateway.ws.ack.batch-enabled/batch-window-ms` 对照回归
- WS：delivered/read ACK 推进从 `imDbExecutor` 隔离到 `imAckExecutor`，并支持按 `(ackUserId, ackType, chatType, chatId)` 1s 窗口合并（可开关）以降低 DB 写放大
- WS：AUTH 后离线补发增加可控开关 `im.gateway.ws.resend.after-auth-enabled`（默认 true；压测可关闭避免历史消息污染统计）
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
- 提交卫生：新增 `.gitattributes`，并完善 `.gitignore` 忽略规则，减少误提交风险
- 稳定性：对关键路径的吞异常补齐最小 debug 日志/注释，降低静默失败定位成本
- WS：单聊 `t_single_chat.updated_at` 增加 1s 窗口去抖（可配置），用于 burst 场景降低 DB 写放大并压尾延迟
- 测试：`ws-cluster-5x-test` 支持透传单聊 updatedAt 去抖开关，并支持跳过 50k connect 阶段（便于高压场景快速回归）
- 单聊：发送主链路移除 `ensureMembers()`（减少每消息 2 次 exists read），delivered/read ACK 改为“优先 update，缺行再补建兜底”，将成员行从强依赖改为按需补齐
- 测试：`ws-cluster-5x-test` 的 `single_e2e_*_avg.json` 补齐 saved 分位数与 dup/reorder 汇总，并修复嵌套 PowerShell 进程触发的 conda 编码噪声
- 网关：Netty worker/boss 线程数支持配置（`im.gateway.ws.worker-threads` / `im.gateway.ws.boss-threads`），便于单机多实例压测时避免线程数爆炸
- 测试：`ws-cluster-5x-test` 支持 `AutoTuneLocalThreads/LoadDrainMs/SkipGroup`，并自动对齐 DB executor/JDBC 连接池/Netty worker 线程以减少 `ERROR internal_error`
- 测试：Java 压测器输出 `errorsByReason` 并支持 `drainMs`，避免“提前关连接”掩盖 3s DB timeout 的错误统计

### 移除
- 小程序：移除微信小程序端（`miniprogram/`）及其知识库索引与模块文档（不再维护）

## [0.0.1-SNAPSHOT] - 2025-12-25

### 新增
- 初始化 helloagents 知识库骨架与基础文档
