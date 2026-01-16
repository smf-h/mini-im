# 变更历史索引

本文档记录所有已完成变更的索引，便于追溯和查询。

---

## 索引

| 时间戳 | 功能名称 | 类型 | 状态 | 方案包路径 |
|--------|----------|------|------|------------|
| 202512251200 | 知识库初始化 | 文档 | ?已完成 | helloagents/ |
| 202512262109 | 单聊可靠投递（ACK + 离线 + 定时补发） | 功能 | ?已完成 | helloagents/history/2025-12/202512262109_ws_singlechat_reliability/ |
| 202512271012 | 鉴权链路测试（WS 握手 + AUTH/REAUTH） | 测试 | ?已完成 | helloagents/history/2025-12/202512271012_auth_chain_test/ |
| 202512271212 | WS REAUTH（续期） | 功能 | ?已完成 | helloagents/history/2025-12/202512271212_ws_reauth/ |
| 202512271320 | 单聊消息 HTTP cursor/list | 功能 | ?已完成 | helloagents/history/2025-12/202512271320_singlechat_message_list/ |
| 202512271429 | 好友申请（WS 落库 + HTTP 列表 + 前端联调） | 功能 | ?已完成 | helloagents/history/2025-12/202512271429_friend_request/ |
| 202512271612 | chat list / friend accept（Vue） | 文档 | 已归档 | helloagents/history/2025-12/202512271612_chat_list_friend_accept_vue/ |
| 202512271835 | 前端（Vue3+TS）联调站点 | 功能 | ?已完成 | helloagents/history/2025-12/202512271835_frontend_vue3_ts/ |
| 202512272007 | WS query token 握手修复 | 修复 | ?已完成 | helloagents/history/2025-12/202512272007_ws_query_token_handshake_fix/ |
| 202512272033 | Long/UID 精度兼容（JS 安全整数） | 修复 | ?已完成 | helloagents/history/2025-12/202512272033_backend_long_id_string/ |
| 202512281245 | 站内通知（好友消息） | 功能 | ?已完成 | helloagents/history/2025-12/202512281245_friend_message_notify/ |
| 202512281305 | 单聊 UI（仿微信） | 功能 | ?已完成 | helloagents/history/2025-12/202512281305_chat_ui_wechat/ |
| 202512281318 | 前端多页面 UI（仿微信） | 功能 | ?已完成 | helloagents/history/2025-12/202512281318_wechat_ui_more_pages/ |
| 202512281332 | 绿白主题（仿微信） | 功能 | ?已完成 | helloagents/history/2025-12/202512281332_wechat_theme_green_white/ |
| 202512281337 | 微信风格细节美化 | 功能 | ?已完成 | helloagents/history/2025-12/202512281337_wechat_ui_polish/ |
| 202512281500 | WS 成员游标：送达/已读/补发 | 功能 | ?已完成 | helloagents/history/2025-12/202512281500_ws_cursor_delivery_read/ |
| 202512281609 | 通知增强 + 数据库自动迁移（Flyway） | 功能 | ?已完成 | helloagents/history/2025-12/202512281609_notify_toast_friend_request_flyway/ |
| 202512281721 | 配置模板双文件（env + values） | 文档 | ?已完成 | helloagents/history/2025-12/202512281721_config_env_templates/ |
| 202512281927 | 小群群聊 + 重要消息稀疏索引（@我/回复我） | 功能 | ?已完成 | helloagents/history/2025-12/202512281927_group_chat_mentions/ |
| 202512282014 | 前端视觉系统 + 核心页通栏重构 | 功能 | ?已完成 | helloagents/history/2025-12/202512282014_wechat_ui_design_system/ |
| 202512282100 | 申请入群(审批) + 个人主页 + FriendCode/GroupCode | 功能 | ?已完成 | helloagents/history/2025-12/202512282100_group_join_friendcode_profile/ |
| 202512291142 | 会话免打扰（DND） | 功能 | ?已完成 | helloagents/history/2025-12/202512291142_dnd/ |
| 202512291645 | 单聊视频通话（WebRTC） | 功能 | ?已完成 | helloagents/history/2025-12/202512291645_webrtc_call/ |
| 202512292209 | 群聊禁言（发言限制） | 功能 | ?已完成 | helloagents/history/2025-12/202512292209_group_speak_mute/ |
| 202512302234 | 违禁词处理（服务端替换） | 功能 | ?已完成 | helloagents/history/2025-12/202512302234_forbidden_words/ |
| 202601022043 | WS 补发/扫库噪声收敛（门禁 + 配置对齐） | 修复 | ?已完成 | helloagents/history/2026-01/202601022043_resend_noise/ |
| 202601041531 | WS handler 整理（抽离 CALL 信令） | 重构 | ?已完成 | helloagents/history/2026-01/202601041531_refactor_ws_call/ |
| 202601041456 | WS handler 整理（抽离补发服务） | 重构 | ?已完成 | helloagents/history/2026-01/202601041456_refactor_ws_handler/ |
| 202601041556 | WS handler 整理（抽离 ACK 处理） | 重构 | ?已完成 | helloagents/history/2026-01/202601041556_refactor_ws_ack/ |
| 202601041605 | WS handler 整理（抽离 FRIEND_REQUEST） | 重构 | ?已完成 | helloagents/history/2026-01/202601041605_refactor_ws_friend_request/ |
| 202601041618 | WS handler 整理（拆分单聊/群聊） | 重构 | ?已完成 | helloagents/history/2026-01/202601041618_refactor_ws_chat_split/ |
| 202601041715 | WS handler 整理（拆分 writer/auth/ping） | 重构 | ?已完成 | helloagents/history/2026-01/202601041715_ws_frame_core_split/ |
| 202601041940 | WS 多实例路由 + 单端登录踢下线（sessionVersion） | 功能 | ?已完成 | helloagents/history/2026-01/202601041940_ws_cluster_single_login/ |
| 202601041958 | Redis 缓存（三项：个人信息/群基本信息/单聊映射） | 优化 | ?已完成 | helloagents/history/2026-01/202601041958_cache_core_profiles_singlechat/ |
| 202601042139 | HTTP 限流（@RateLimit + AOP + Redis Lua） | 功能 | ?已完成 | helloagents/history/2026-01/202601042139_rate_limit/ |
| 202601051826 | WS 发送者消息不乱序（per-channel Future 链） | 优化 | ?已完成 | helloagents/history/2026-01/202601051826_ws_sender_order/ |
| 202601052050 | 群聊大群优化（按实例分组批量 PUSH） | 优化 | ?已完成 | helloagents/history/2026-01/202601052050_group_fanout_by_instance/ |
| 202601052110 | 群聊策略切换（推消息体/通知后拉取/不推兜底） | 优化 | ?已完成 | helloagents/history/2026-01/202601052110_group_chat_strategy_switch/ |
| 202601052113 | Redis 幂等强化（ClientMsgId，TTL=1800s） | 优化 | ?已完成 | helloagents/history/2026-01/202601052113_idempotency_redis/ |
| 202601061958 | 朋友圈（Moments）MVP | 功能 | ?已完成 | helloagents/history/2026-01/202601061958_moments_mvp/ |
| 202601062236 | 消息撤回（2分钟，仅发送者） | 功能 | ?已完成 | helloagents/history/2026-01/202601062236_message_revoke/ |
| 202601062334 | 微信小程序端（原生+TS，单页） | 功能 | ?已完成 | helloagents/history/2026-01/202601062334_wechat_miniprogram/ |
| 202601072156 | 待提交代码质量审查与收敛 | 维护 | ?已完成 | helloagents/history/2026-01/202601072156_precommit_quality_review/ |
| 202601082320 | WS 压测与可靠性快测（单机） | 测试 | ?已完成 | helloagents/history/2026-01/202601082320_perf_loadtest_eval/ |
| 202601090120 | WS 慢消费者背压保护 | 修复 | ?已完成 | helloagents/history/2026-01/202601090120_ws_backpressure/ |
| 202601090130 | WS 慢消费者背压治理（硬化方案） | 方案 | ?已完成 | helloagents/history/2026-01/202601090130_ws_backpressure_hardening/ |
| 202601121934 | Codex 任务结束弹窗提醒（脚本） | 维护 | ?已完成 | helloagents/history/2026-01/202601121934_codex_popup/ |
| 202601121939 | 单聊 ACK 回切隔离（eventLoop 减负） | 修复 | ?已完成 | helloagents/history/2026-01/202601121939_ws_ack_eventloop_isolation/ |
| 202601122018 | Codex 全局完成弹窗（notify hook） | 维护 | ?已完成 | helloagents/history/2026-01/202601122018_codex_global_notify_popup/ |
| 202601131248 | Web 端 UI 微调（仿微信三栏） | 重构 | ?已完成 | helloagents/history/2026-01/202601131248_web-ui-revamp/ |
| 202601131700 | 单聊尾延迟治理预研（WS 编码 offload / post-db 隔离） | 方案 | ?已完成 | helloagents/history/2026-01/202601131700_ws_single_encode_postdb_isolation/ |
| 202601131424 | 单聊尾延迟治理（encode 开关对照 + writer 串行优化） | 优化 | ?已完成 | helloagents/history/2026-01/202601131424_ws_single_dbqueue_postdb_offload/ |
| 202601131631 | 固定速率压测（open-loop）+ delivered/read ACK 合并开关 | 优化 | ?已完成 | helloagents/history/2026-01/202601131631_ws_openloop_ack_isolation/ |
| 202601140018 | 单聊尾延迟收敛（入站队列上限 + 会话 updatedAt 去抖） | 优化 | ?已完成 | helloagents/history/2026-01/202601140018_ws_single_inbound_queue_updatedat_debounce/ |
| 202601141742 | 单聊主链路提速（updated_at 异步化 + 串行队列 inline + 5 实例均衡 pinning） | 优化 | ?已完成 | helloagents/history/2026-01/202601141742_ws_single_mainchain_speed/ |
| 202601141925 | 单聊性能回归（DB 线程池×连接池对齐 + 队列收紧） | 优化 | ?已完成 | helloagents/history/2026-01/202601141925_ws_single_dbpool_hikari_alignment/ |
| 202601150002 | 单聊 DB 减负（ensureMembers 去热路径） | 优化 | ?已完成 | helloagents/history/2026-01/202601150002_singlechat_ensure_members_db_relief/ |
| 202601151243 | 单聊“两级回执”（ACK accepted/saved）+ 异步落库/投递（Redis Streams） | 优化 | ?已完成 | helloagents/history/2026-01/202601151243_ws_singlechat_two_phase_ack/ |

---

## 按月归档

### 2026-01

- 202601022043_resend_noise - WS 补发/扫库噪声收敛（门禁 + 配置对齐）
- 202601041531_refactor_ws_call - WS handler 整理（抽离 CALL 信令）
- 202601041456_refactor_ws_handler - WS handler 整理（抽离补发服务）
- 202601041556_refactor_ws_ack - WS handler 整理（抽离 ACK 处理）
- 202601041605_refactor_ws_friend_request - WS handler 整理（抽离 FRIEND_REQUEST）
- 202601041618_refactor_ws_chat_split - WS handler 整理（拆分单聊/群聊）
- 202601041715_ws_frame_core_split - WS handler 整理（拆分 writer/auth/ping）
- 202601041940_ws_cluster_single_login - WS 多实例路由 + 单端登录踢下线（sessionVersion）
- 202601041958_cache_core_profiles_singlechat - Redis 缓存（三项：个人信息/群基本信息/单聊映射）
- 202601042139_rate_limit - HTTP 限流（@RateLimit + AOP + Redis Lua）
- 202601051826_ws_sender_order - WS 发送者消息不乱序（per-channel Future 链）
- 202601052050_group_fanout_by_instance - 群聊大群优化（按实例分组批量 PUSH）
- 202601052110_group_chat_strategy_switch - 群聊策略切换（推消息体/通知后拉取/不推兜底）
- 202601052113_idempotency_redis - Redis 幂等强化（ClientMsgId，TTL=1800s）
- 202601061958_moments_mvp - 朋友圈（Moments）MVP
- 202601062236_message_revoke - 消息撤回（2分钟，仅发送者）
- 202601062334_wechat_miniprogram - 微信小程序端（原生+TS，单页）
- 202601072156_precommit_quality_review - 待提交代码质量审查与收敛
- 202601082320_perf_loadtest_eval - WS 压测与可靠性快测（单机）
- 202601090120_ws_backpressure - WS 慢消费者背压保护
- 202601090130_ws_backpressure_hardening - WS 慢消费者背压治理（硬化方案）
- 202601121934_codex_popup - Codex 任务结束弹窗提醒（脚本）
- 202601121939_ws_ack_eventloop_isolation - 单聊 ACK 回切隔离（eventLoop 减负）
- 202601122018_codex_global_notify_popup - Codex 全局完成弹窗（notify hook）
- 202601131248_web-ui-revamp - Web 端 UI 微调（仿微信三栏）
- 202601131700_ws_single_encode_postdb_isolation - 单聊尾延迟治理预研（WS 编码 offload / post-db 隔离）
- 202601131424_ws_single_dbqueue_postdb_offload - 单聊尾延迟治理（encode 开关对照 + writer 串行优化）
- 202601131631_ws_openloop_ack_isolation - 固定速率压测（open-loop）+ delivered/read ACK 合并开关
- 202601140018_ws_single_inbound_queue_updatedat_debounce - 单聊尾延迟收敛（入站队列上限 + 会话 updatedAt 去抖）
- 202601141742_ws_single_mainchain_speed - 单聊主链路提速（updated_at 异步化 + 串行队列 inline + 5 实例均衡 pinning）
- 202601141925_ws_single_dbpool_hikari_alignment - 单聊性能回归（DB 线程池×连接池对齐 + 队列收紧）
- 202601150002_singlechat_ensure_members_db_relief - 单聊 DB 减负（ensureMembers 去热路径）
- 202601151243_ws_singlechat_two_phase_ack - 单聊“两级回执”（ACK accepted/saved）+ 异步落库/投递（Redis Streams）

### 2025-12

- 202512302234_forbidden_words - 违禁词处理（服务端替换）
- 202512292209_group_speak_mute - 群聊禁言（发言限制）
- 202512291645_webrtc_call - 单聊视频通话（WebRTC）
- 202512291142_dnd - 会话免打扰（DND）
- 202512282014_wechat_ui_design_system - 前端微信绿白视觉系统 + 组件化重构
- 202512282100_group_join_friendcode_profile - 申请入群(审批) + 个人主页 + FriendCode/GroupCode
- 202512281927_group_chat_mentions - 小群群聊 + 重要消息稀疏索引（@我/回复我）
- 202512281609_notify_toast_friend_request_flyway - 通知增强 + 数据库自动迁移（Flyway）
- 202512281721_config_env_templates - 配置模板双文件（env + values）
- 202512281500_ws_cursor_delivery_read - WS 成员游标：送达/已读/补发

- 202512251200_kb_init - 初始化 helloagents 知识库
- 202512262109_ws_singlechat_reliability - 单聊可靠投递（ACK + 离线 + 定时补发）
- 202512271012_auth_chain_test - 鉴权链路测试（WS 握手 + AUTH/REAUTH）
- 202512271212_ws_reauth - WS REAUTH（续期）
- 202512271320_singlechat_message_list - 单聊消息 HTTP cursor/list
- 202512271429_friend_request - 好友申请（WS 落库 + HTTP 列表 + 前端联调）
- 202512271835_frontend_vue3_ts - 前端（Vue3+TS）联调站点
- 202512272007_ws_query_token_handshake_fix - WS query token 握手修复
- 202512272033_backend_long_id_string - Long/UID 精度兼容（JS 安全整数）
- 202512281245_friend_message_notify - 站内通知（好友消息）
- 202512281305_chat_ui_wechat - 单聊 UI（仿微信）
- 202512281318_wechat_ui_more_pages - 前端多页面 UI（仿微信）
- 202512281332_wechat_theme_green_white - 绿白主题（仿微信）
- 202512281337_wechat_ui_polish - 微信风格细节美化
