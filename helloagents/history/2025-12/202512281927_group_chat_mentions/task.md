# 任务清单：小群群聊 + 重要消息（@我/回复我）

任务：
- [√] DB：新增 `t_message_mention`（稀疏索引表），并加入 Flyway 迁移（V2）
- [√] 后端：扩展 WS `WsEnvelope` 支持 `mentions/replyToServerMsgId/important`
- [√] 后端：实现 `GROUP_CHAT` 落库 + 成员校验 + 在线推送（按接收方计算 `important`）
- [√] 后端：补发链路（resend pending）为群消息补齐 `important`
- [√] 后端：新增群相关 HTTP API
  - [√] `POST /group/create`
  - [√] `GET /group/conversation/cursor`（未读 + @未读 + lastMessage）
  - [√] `GET /group/message/cursor`
  - [√] `GET /group/basic`（前端缓存群名）
- [√] 前端：新增群列表页 `/groups` 与群聊天页 `/group/:groupId`
- [√] 前端：发送群消息支持 `@123` 解析为 mentions
- [√] 前端：`AppShellView` 支持群重要消息 toast（`important=true`）
- [√] 文档：同步更新 `helloagents/wiki/api.md`、`helloagents/wiki/data.md`、`helloagents/CHANGELOG.md`
- [√] 验证：`mvn test`、`npm -C frontend run build`
