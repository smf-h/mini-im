# 轻量迭代：拆分 WS ACK 逻辑

目标：把 `WsFrameHandler` 中 ACK（送达/已读）处理抽离为独立组件，减少 handler 体积，便于后续继续拆 chat/friend_request。

任务清单：
- [√] 新增 `WsAckHandler`：承载 `ACK` 校验、按 `serverMsgId` 查消息、推进成员游标、回执给发送方
- [√] `WsFrameHandler`：`case "ACK"` 改为委托 `WsAckHandler`
- [√] `NettyWsServer`：注入并传递 `WsAckHandler`
- [√] 删除 `WsFrameHandler` 内旧 ACK 相关方法与导入
- [√] 验证：`mvn test`、`npm -C frontend run build`
