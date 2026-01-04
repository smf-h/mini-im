# 轻量迭代：整理 WS handler 结构

目标：降低 `WsFrameHandler` 体积与职责耦合，把“离线补发/兜底补发”抽到独立组件，减少重复代码，便于后续继续拆分 call/chat/ack。

任务清单：
- [√] 新增 `WsResendService`：封装 DB 拉取 pending 消息 + important 标记 + 写入 WS
- [√] `WsFrameHandler` 改为调用 `WsResendService`，自身只保留门禁/鉴权逻辑
- [√] `WsCron` 改为调用 `WsResendService`，移除重复的查询与序列化写入逻辑
- [√] 运行 `mvn test`、`npm -C frontend run build` 验证
