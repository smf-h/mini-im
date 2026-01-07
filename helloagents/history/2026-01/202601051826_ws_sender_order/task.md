# 任务清单: WS 发送者消息不乱序（单聊/群聊）

目录: `helloagents/plan/202601051826_ws_sender_order/`

---

## 1. Channel 串行队列（Future 链）
- [√] 1.1 新增 `src/main/java/com/miniim/gateway/ws/WsChannelSerialQueue.java`：为每个 `Channel` 维护 tail Future，并提供 `enqueue` API，验证 why.md#需求-单聊-发送者消息不乱序-场景-同一连接连续发送两条单聊消息
- [√] 1.2 新增 `src/test/java/com/miniim/gateway/ws/WsChannelSerialQueueTest.java`：覆盖“后任务不会早于前任务执行”的顺序保证

## 2. 单聊：发送者不乱序
- [√] 2.1 修改 `src/main/java/com/miniim/gateway/ws/WsSingleChatHandler.java`：将“落库→ACK/ERROR→push”封装为队列任务并通过 `enqueue` 串行执行，验证 why.md#需求-单聊-发送者消息不乱序-场景-同一连接连续发送两条单聊消息

## 3. 群聊：发送者不乱序
- [√] 3.1 修改 `src/main/java/com/miniim/gateway/ws/WsGroupChatHandler.java`：将“校验→落库→ACK/ERROR→push”封装为队列任务并通过 `enqueue` 串行执行，验证 why.md#需求-群聊-发送者消息不乱序-场景-同一连接连续发送两条群聊消息

## 4. 安全检查
- [√] 4.1 执行安全检查（按G9: 输入验证、敏感信息处理、权限控制、避免 eventLoop 阻塞、失败释放队列避免 DoS）

## 5. 文档更新
- [√] 5.1 更新 `helloagents/wiki/modules/gateway.md`：补充“发送者不乱序（per-channel Future 链）”与测试方法说明
- [√] 5.2 更新 `helloagents/CHANGELOG.md`：记录本次顺序保障变更

## 6. 测试
- [√] 6.1 运行 `mvn test`，确保所有测试通过
- [-] 6.2 运行本地手工验证：同一连接连续发送两条单聊/群聊消息，观察 ACK 与对端到达顺序一致（需要你本地启动两端联调验证）
