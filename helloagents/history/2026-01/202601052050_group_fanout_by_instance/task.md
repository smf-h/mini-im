# 任务清单: 群聊大群优化（按实例分组批量 PUSH）

目录: `helloagents/plan/202601052050_group_fanout_by_instance/`

---

## 1. 批量 PUSH 消息模型（兼容单用户）
- [√] 1.1 修改 `src/main/java/com/miniim/gateway/ws/cluster/WsClusterMessage.java`：新增 `userIds` 字段并保持旧 `userId` 逻辑可用，验证 why.md#需求-群聊-按实例分组下发逻辑扇出-场景-千人群在线分布多实例
- [√] 1.2 修改 `src/main/java/com/miniim/gateway/ws/cluster/WsClusterBus.java`：新增 `publishPushBatch(serverId, userIds, envelope)`，并支持每批 500，验证 why.md#需求-群聊-按实例分组下发逻辑扇出-场景-千人群在线分布多实例
- [√] 1.3 修改 `src/main/java/com/miniim/gateway/ws/cluster/WsClusterListener.java`：支持批量 `userIds` 写出与旧单用户写出兼容，验证 why.md#需求-群聊-按实例分组下发逻辑扇出-场景-千人群在线分布多实例

## 2. 路由批量查询与分组
- [√] 2.1 新增 `GroupFanoutService`（或同等组件）：实现 `MGET im:gw:route:{uid}` + 按 `serverId` 分组 + 每批 500 切片下发，验证 why.md#需求-群聊-按实例分组下发逻辑扇出-场景-千人群在线分布多实例
- [√] 2.2 在 `GroupFanoutService` 中实现 `important/normal` 分流，验证 why.md#需求-群聊-important-语义保持-场景-群内-我回复我

## 3. 群聊下发改造（GROUP_CHAT）
- [√] 3.1 修改 `src/main/java/com/miniim/gateway/ws/WsGroupChatHandler.java`：把群聊下发从“逐成员 pushToUser”改为调用 `GroupFanoutService` 的按实例分组批量下发，验证 why.md#需求-群聊-按实例分组下发逻辑扇出-场景-千人群在线分布多实例

## 4. 配置与降级策略
- [√] 4.1 新增配置项（如 `im.gateway.group-fanout.enabled/batch-size/max-publish-users`），默认启用（1A），批量大小 500（2A），验证 why.md#需求-群聊-按实例分组下发逻辑扇出-场景-千人群在线分布多实例
- [√] 4.2 Redis MGET 失败时的降级策略（fail-open）：退化为“逐成员 pushToUser”或“仅本机直推”，并保持不阻塞 eventLoop

## 5. 安全检查
- [√] 5.1 执行安全检查（按G9: 输入验证、敏感信息处理、权限控制、避免 eventLoop 阻塞、payload 大小控制）

## 6. 文档更新
- [√] 6.1 更新 `helloagents/wiki/modules/gateway.md`：补充“群聊按实例分组批量 PUSH”的实现与测试说明
- [√] 6.2 更新 `helloagents/CHANGELOG.md`：记录群聊大群优化能力

## 7. 测试
- [√] 7.1 单元测试：分组/切片/important 分流正确性；批量 PUSH 兼容单用户 PUSH
- [√] 7.2 运行 `mvn test`
- [-] 7.3 两实例手工验证：同群成员分布两实例，验证 publish 次数下降且消息可达（需要你本地起两实例联调）
