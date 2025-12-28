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
| 202512272033 | Long/UID 精度兼容（JS 安全整数） | 修复 | ✅已完成 | helloagents/history/2025-12/202512272033_backend_long_id_string/ |
| 202512281245 | 站内通知（好友消息） | 功能 | ✅已完成 | helloagents/history/2025-12/202512281245_friend_message_notify/ |
| 202512281305 | 单聊 UI（仿微信） | 功能 | ✅已完成 | helloagents/history/2025-12/202512281305_chat_ui_wechat/ |
| 202512281318 | 前端多页面 UI（仿微信） | 功能 | ✅已完成 | helloagents/history/2025-12/202512281318_wechat_ui_more_pages/ |
| 202512281332 | 绿白主题（仿微信） | 功能 | ✅已完成 | helloagents/history/2025-12/202512281332_wechat_theme_green_white/ |
| 202512281337 | 微信风格细节美化 | 功能 | ✅已完成 | helloagents/history/2025-12/202512281337_wechat_ui_polish/ |
| 202512281500 | WS 成员游标：送达/已读/补发 | 功能 | ✅已完成 | helloagents/history/2025-12/202512281500_ws_cursor_delivery_read/ |
| 202512281609 | 通知增强 + 数据库自动迁移（Flyway） | 功能 | ✅已完成 | helloagents/history/2025-12/202512281609_notify_toast_friend_request_flyway/ |
| 202512281721 | 配置模板双文件（env + values） | 文档 | ✅已完成 | helloagents/history/2025-12/202512281721_config_env_templates/ |
| 202512281927 | 小群群聊 + 重要消息稀疏索引（@我/回复我） | 功能 | ✅已完成 | helloagents/history/2025-12/202512281927_group_chat_mentions/ |
| 202512282014 | 前端视觉系统 + 核心页通栏重构 | 功能 | ✅已完成 | helloagents/history/2025-12/202512282014_wechat_ui_design_system/ |
| 202512282100 | 申请入群(审批) + 个人主页 + FriendCode/GroupCode | 功能 | ✅已完成 | helloagents/history/2025-12/202512282100_group_join_friendcode_profile/ |

---

## 按月归档

### 2025-12

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
