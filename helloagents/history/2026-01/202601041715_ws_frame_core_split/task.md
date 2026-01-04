# 任务清单: WS 核心能力拆分（Writer/Auth/Ping）

目录: `helloagents/history/2026-01/202601041715_ws_frame_core_split/`

---

## 1. WS Writer
- [√] 1.1 新增 `src/main/java/com/miniim/gateway/ws/WsWriter.java`，统一 `write/writeAck/writeError`，验证 why.md#需求-ws-写出统一-场景-handler-在任意线程发消息

## 2. WS Auth
- [√] 2.1 新增 `src/main/java/com/miniim/gateway/ws/WsAuthHandler.java`，迁移 `AUTH/REAUTH` 逻辑，验证 why.md#需求-ws-认证拆分-场景-auth-成功触发补发一次
- [√] 2.2 改造 `src/main/java/com/miniim/gateway/ws/WsFrameHandler.java`：AUTH/REAUTH 委托 `WsAuthHandler`

## 3. WS Ping
- [√] 3.1 新增 `src/main/java/com/miniim/gateway/ws/WsPingHandler.java`，实现 `PING/PONG` 与 writer-idle ping，验证 why.md#需求-ws-心跳拆分-场景-writer_idle-维持在线-ttl
- [√] 3.2 改造 `src/main/java/com/miniim/gateway/ws/WsFrameHandler.java`：PING 委托 `WsPingHandler`，writer-idle 委托 `WsPingHandler`

## 4. 统一写出改造
- [√] 4.1 改造 `src/main/java/com/miniim/gateway/ws/WsSingleChatHandler.java` 使用 `WsWriter`
- [√] 4.2 改造 `src/main/java/com/miniim/gateway/ws/WsGroupChatHandler.java` 使用 `WsWriter`
- [√] 4.3 改造 `src/main/java/com/miniim/gateway/ws/WsFriendRequestHandler.java` 使用 `WsWriter`
- [√] 4.4 改造 `src/main/java/com/miniim/gateway/ws/WsAckHandler.java` 使用 `WsWriter`
- [√] 4.5 改造 `src/main/java/com/miniim/gateway/ws/WsCallHandler.java` 使用 `WsWriter`（保持 `CALL_ERROR` 不变）
- [√] 4.6 改造 `src/main/java/com/miniim/gateway/ws/WsResendService.java` 使用 `WsWriter`

## 5. 注入与构建
- [√] 5.1 改造 `src/main/java/com/miniim/gateway/ws/NettyWsServer.java`：注入并传递 `WsWriter/WsAuthHandler/WsPingHandler`

## 6. 安全检查
- [√] 6.1 执行安全检查（按G9: 输入验证、敏感信息处理、权限控制、EHRB风险规避）

## 7. 文档更新
- [√] 7.1 更新 `helloagents/wiki/modules/gateway.md`
- [√] 7.2 更新 `helloagents/CHANGELOG.md`

## 8. 测试
- [√] 8.1 执行 `mvn test`
- [√] 8.2 执行 `npm -C frontend run build`
