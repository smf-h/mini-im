# 任务清单（已归档）

# 任务清单
- [√] 分支保护调整：required_pull_request_reviews 关闭（审核数=0），保留 required_status_checks(build)
- [-] 控制器：SingleChatController（/api/single-chat）
- [-] DTO：SendMessageRequest/Response、MessageDTO、HistoryResponse
- [-] 应用服务：SingleChatAppService（复用 ClientMsgIdIdempotency + SessionRegistry）
- [-] 历史查询：cursor 分页（id DESC）
- [-] API 文档：wiki/api.md 增补单聊 REST
- [-] 变更记录：CHANGELOG 添加“单聊 v1（REST + 历史）”
- [-] PR：feat/single-chat-v1 → master（受保护分支，走 CI）

> 说明：该方案已归档，后续以 WebSocket 优先新方案替代。
