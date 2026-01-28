# 轻量迭代：拆分 WS FRIEND_REQUEST 逻辑

目标：把 `WsFrameHandler` 中好友申请（`FRIEND_REQUEST`）处理抽离为独立组件，进一步降低 handler 体积与耦合。

任务清单：
- [√] 新增 `WsFriendRequestHandler`：承载校验、幂等 claim、落库、ACK(saved) 与 best-effort 推送
- [√] `WsFrameHandler`：`case "FRIEND_REQUEST"` 改为委托 `WsFriendRequestHandler`
- [√] `NettyWsServer`：注入并传递 `WsFriendRequestHandler`
- [√] 删除 `WsFrameHandler` 内旧 FRIEND_REQUEST 相关方法与导入
- [√] 验证：`mvn test`、`npm -C frontend run build`
