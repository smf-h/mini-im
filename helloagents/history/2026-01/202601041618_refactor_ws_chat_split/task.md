# 轻量迭代：拆分 WS chat handler（单聊/群聊分别）

目标：把 `WsFrameHandler` 中 `SINGLE_CHAT/GROUP_CHAT` 处理分别抽离到独立组件，降低 handler 体积与耦合，后续便于继续抽公共 writer。

任务清单：
- [√] 新增 `WsSingleChatHandler`：承载单聊校验、幂等、落库 ACK(saved)、在线 best-effort 下发
- [√] 新增 `WsGroupChatHandler`：承载群聊校验（含禁言）、幂等、落库 ACK(saved)、@/reply mention 落库与重要下发
- [√] `WsFrameHandler`：`case SINGLE_CHAT/GROUP_CHAT` 改为委托上述 handler
- [√] `NettyWsServer`：注入并传递上述 handler；清理 `WsFrameHandler` 不再需要的依赖
- [√] 验证：`mvn test`、`npm -C frontend run build`
