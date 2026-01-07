# 任务清单: 消息撤回（2分钟，仅发送者）

目录: `helloagents/plan/202601062236_message_revoke/`

---

## 1. 协议与 WS Handler（gateway）
- [√] 1.1 在 `src/main/java/com/miniim/gateway/ws/WsFrameHandler.java` 增加 `MESSAGE_REVOKE` 路由，验证 why.md#核心场景-需求-撤回单聊消息-场景-发送者在-2-分钟内撤回成功
- [√] 1.2 新增 `src/main/java/com/miniim/gateway/ws/WsMessageRevokeHandler.java`（或等价位置）实现撤回请求处理：鉴权/参数校验/查询消息/权限与时限校验，验证 why.md#核心场景-需求-撤回单聊消息-场景-发送者在-2-分钟内撤回成功
- [-] 1.3 在 `src/main/java/com/miniim/gateway/ws/WsEnvelope.java` 补充撤回所需字段（如需要），并在 `helloagents/wiki/api.md` 同步协议说明，验证 why.md#变更内容
  > 备注: 复用现有 `serverMsgId/from/to/groupId/ts` 字段即可；已在 `helloagents/wiki/api.md` 补充协议说明

## 2. 消息状态更新与幂等（domain）
- [-] 2.1 在 `src/main/java/com/miniim/domain/service/MessageService.java` / `src/main/java/com/miniim/domain/service/impl/MessageServiceImpl.java` 新增撤回方法（条件更新：`status!=REVOKED`），验证 why.md#核心场景-需求-撤回单聊消息-场景-重复撤回幂等
  > 备注: 本次直接在 `WsMessageRevokeHandler` 内使用 MyBatis-Plus 条件更新实现幂等，暂未抽公共 service 方法
- [√] 2.2 单聊撤回后更新会话更新时间：`src/main/java/com/miniim/domain/service/SingleChatService.java` 相关更新点，验证 why.md#核心场景-需求-撤回单聊消息-场景-发送者在-2-分钟内撤回成功
- [√] 2.3 群聊撤回后更新群更新时间：`src/main/java/com/miniim/domain/service/GroupService.java` 相关更新点，验证 why.md#核心场景-需求-撤回群聊消息-场景-发送者在-2-分钟内撤回成功并广播

## 3. WS 推送与离线补发一致性（gateway）
- [√] 3.1 在 `src/main/java/com/miniim/gateway/ws/WsResendService.java` 中对 `MessageStatus.REVOKED` 做 body 脱敏（下发“已撤回”），验证 why.md#核心场景-需求-撤回单聊消息-场景-发送者在-2-分钟内撤回成功
- [√] 3.2 群聊撤回通知广播：复用 `GroupMemberIdsCache` 或 `GroupMemberMapper` 获取成员并推送，验证 why.md#核心场景-需求-撤回群聊消息-场景-发送者在-2-分钟内撤回成功并广播

## 4. HTTP 历史与会话 lastMessage 脱敏（domain/controller）
- [√] 4.1 将 `src/main/java/com/miniim/domain/controller/SingleChatMessageController.java` 的输出改为撤回脱敏（不返回原文），验证 why.md#核心场景-需求-撤回单聊消息-场景-发送者在-2-分钟内撤回成功
  > 备注: 通过 `MessageEntity` JSON 序列化对 `content` 统一脱敏实现，controller 无需改动
- [√] 4.2 将 `src/main/java/com/miniim/domain/controller/GroupMessageController.java` 的输出改为撤回脱敏（不返回原文），验证 why.md#核心场景-需求-撤回群聊消息-场景-发送者在-2-分钟内撤回成功并广播
  > 备注: 通过 `MessageEntity` JSON 序列化对 `content` 统一脱敏实现，controller 无需改动
- [√] 4.3 将 `src/main/java/com/miniim/domain/controller/SingleChatConversationController.java` / `src/main/java/com/miniim/domain/controller/GroupConversationController.java` 的 lastMessage 脱敏，验证 why.md#变更内容

## 5. 前端交互与展示（frontend）
- [√] 5.1 在群聊/单聊视图中为“我发送的消息且≤2分钟”增加撤回入口，验证 why.md#核心场景-需求-撤回单聊消息-场景-发送者在-2-分钟内撤回成功
- [√] 5.2 发送 `MESSAGE_REVOKE` 并处理服务端确认（ACK 或 `MESSAGE_REVOKED`），将目标消息替换展示为“已撤回”，验证 why.md#核心场景-需求-撤回单聊消息-场景-发送者在-2-分钟内撤回成功
- [√] 5.3 处理接收侧 `MESSAGE_REVOKED` 推送：按 `serverMsgId` 定位消息并更新 UI，验证 why.md#核心场景-需求-撤回群聊消息-场景-发送者在-2-分钟内撤回成功并广播

## 6. 安全检查
- [√] 6.1 执行安全检查：权限校验（仅发送者）、时限校验（2分钟）、输入校验（serverMsgId/群成员关系）、错误码不泄露敏感信息，按G9要求

## 7. 文档与变更记录
- [√] 7.1 更新 `helloagents/wiki/api.md` 增加撤回 WS 协议与错误原因；必要时更新 `helloagents/wiki/modules/gateway.md` / `helloagents/wiki/modules/frontend.md`
- [√] 7.2 更新 `helloagents/CHANGELOG.md` 记录“消息撤回”新增能力

## 8. 测试
- [-] 8.1 在 `scripts/ws-smoke-test/WsSmokeTest.java` 增加撤回场景（发送→撤回→对端收到撤回事件→HTTP 拉取为“已撤回”），验证 why.md#核心场景
  > 备注: 暂未补充 WS 冒烟脚本；本次通过 `mvn test` 验证编译与单测通过
- [-] 8.2 增加边界测试：超时撤回失败、非发送者撤回失败、重复撤回幂等
  > 备注: 相关边界已在服务端逻辑实现，待后续补齐端到端测试覆盖
