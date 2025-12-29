# 任务清单: 单聊视频通话（WebRTC）

目录: `helloagents/plan/202512291645_webrtc_call/`

---

## 1. WS 信令协议（gateway + frontend）
- [√] 1.1 在 `src/main/java/com/miniim/gateway/ws/WsEnvelope.java` 扩展 CALL_* 所需字段（callId/sdp/ice 等），验证 why.md#核心场景-需求-发起视频通话-场景-对方在线并接听
- [√] 1.2 在 `src/main/java/com/miniim/gateway/ws/WsFrameHandler.java` 新增 CALL_* 信令处理与转发（在线/忙线/超时），验证 why.md#核心场景-需求-发起视频通话-场景-对方拒绝-忙线 与 why.md#核心场景-需求-发起视频通话-场景-对方未响应（未接来电）
- [√] 1.3 在 `frontend/src/types/ws.ts` 扩展 CALL_* 字段类型，并在 `frontend/src/stores/ws.ts` 事件流中保留信令事件，验证 why.md#核心场景-需求-发起视频通话-场景-对方在线并接听

## 2. 通话记录（db + domain + http）
- [√] 2.1 在 `src/main/resources/db/migration/` 新增 Flyway 迁移创建 `t_call_record`，验证 why.md#核心场景-需求-通话记录-场景-事后查看
- [√] 2.2 新增 `CallRecordEntity/Mapper/Service`（`src/main/java/com/miniim/domain/*`），并在 CALL_* 状态流转中写入/更新记录，验证 why.md#核心场景-需求-发起视频通话-场景-对方未响应（未接来电）
- [√] 2.3 新增 `CallRecordController` 提供 cursor/list 查询接口（只允许查询本人相关记录），验证 why.md#核心场景-需求-通话记录-场景-事后查看

## 3. 前端通话 UI（WebRTC）
- [√] 3.1 新增 `frontend/src/stores/call.ts`（通话状态机 + 信令收发），验证 why.md#核心场景-需求-发起视频通话-场景-对方在线并接听
- [√] 3.2 在 `frontend/src/components/AppLayout.vue` 接入“来电弹窗/通话面板”全局覆盖层，验证 why.md#核心场景-需求-发起视频通话-场景-对方在线并接听
- [√] 3.3 在 `frontend/src/views/ChatView.vue` 增加“视频通话”按钮与通话入口，验证 why.md#核心场景-需求-发起视频通话-场景-对方在线并接听
- [√] 3.4 在通话面板实现静音/关摄像头（track.enabled），验证 why.md#核心场景-需求-通话中控制-场景-静音 与 why.md#核心场景-需求-通话中控制-场景-关闭摄像头

## 4. 安全检查
- [√] 4.1 执行安全检查（按G9: 权限校验/输入校验/敏感信息处理/限流），重点：不记录 SDP/ICE；CALL_* 仅允许 from/to 双方

## 5. 文档更新
- [√] 5.1 更新 `helloagents/wiki/api.md` 补充 CALL_* WS 事件与通话记录 HTTP API
- [√] 5.2 更新 `helloagents/wiki/modules/gateway.md` 与 `helloagents/wiki/modules/domain.md` 补充通话信令与通话记录模型
- [√] 5.3 更新 `helloagents/CHANGELOG.md` 记录“单聊视频通话（Phase1）”

## 6. 测试
- [√] 6.1 执行 `npm -C frontend run build` 验证前端编译通过
- [√] 6.2 执行 `mvn test` 验证后端编译与测试通过
- [?] 6.3 手工验收（同浏览器双窗口）：呼叫/接听/拒绝/超时/挂断/静音/关摄像头 + 记录可查
