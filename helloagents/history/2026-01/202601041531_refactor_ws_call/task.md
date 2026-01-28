# 轻量迭代：拆分 WS CALL 逻辑

目标：把 `WsFrameHandler` 中 `CALL_*`（WebRTC 信令）相关逻辑抽离成独立组件，降低 handler 体积与耦合，便于后续继续拆 chat/ack。

任务清单：
- [√] 新增 `WsCallHandler`：承载 `CALL_INVITE/ACCEPT/REJECT/CANCEL/END/ICE`、timeout、断线收尾、校验与落库
- [√] `WsFrameHandler`：switch 里把 `CALL_*` 分支改为委托 `WsCallHandler`
- [√] `NettyWsServer`：注入并传递 `WsCallHandler`；`WsFrameHandler` 移除 call 相关字段/常量
- [√] 运行 `mvn test`、`npm -C frontend run build` 验证
