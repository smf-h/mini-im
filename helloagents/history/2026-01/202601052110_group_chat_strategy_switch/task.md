# 群聊策略切换（策略1 + 策略2）- 任务清单

> 口径与默认值已确认：在线人数 `1A`；阈值 `2默认`；策略2 `3B`；兜底启用。

## 0. 预检与边界
- [√] 0.1 盘点当前群聊发送链路与跨实例推送实现位置（群聊 handler / push service / cluster bus / route store）
- [√] 0.2 明确群消息落库后用于通知/拉取的游标字段（msgId 或 seq），并统一口径（使用 msgId/id）
- [√] 0.3 确认 group member cache 的 key/TTL/主动失效点已覆盖成员变更（本任务仅复核）

## 1. 配置化策略选择参数
- [√] 1.1 增加策略阈值配置项与默认值：
  - `im.group-chat.strategy.group-size-threshold = 2000`
  - `im.group-chat.strategy.online-user-threshold = 500`
  - `im.group-chat.strategy.notify-max-online-user = 2000`
  - `im.group-chat.strategy.huge-group-no-notify-size = 10000`
- [√] 1.2 增加配置文档（wiki/modules/gateway.md 或 wiki/modules/cache.md 补充）

## 2. 批量路由查询与按实例分组
- [√] 2.1 实现对成员 `userIds` 的批量路由查询（MGET / multiGet），并按 `serverId` 分组
- [√] 2.2 在线用户口径按 `userId` 去重（符合 `1A`）
- [√] 2.3 分批处理（默认每批 500）避免 Redis/内存尖峰

## 3. 批量 PUSH 协议与控制通道改造（向后兼容）
- [√] 3.1 扩展 cluster message 支持 `userIds[]` 批量推送（保留 `userId` 单推字段）
- [√] 3.2 扩展 cluster bus 支持按 `serverId` 发布“批量 PUSH/批量通知”
- [√] 3.3 扩展 cluster listener：收到批量后在本机遍历写入对应 Channel

## 4. 落地策略1（推消息体）
- [√] 4.1 群聊发送：成员从 Redis Set 获取（miss 回源 DB 回填）
- [√] 4.2 在线路由批查后按实例分组：
  - 本机 server：直接写 Channel
  - 其他 server：批量 publish 到目标 server
- [√] 4.3 确保所有 Redis/DB IO 在业务线程池执行，Netty 写回切回 `eventLoop`

## 5. 落地策略2（通知后拉取）+ 兜底
- [√] 5.1 定义“新消息通知”payload（不含消息体，含 groupId + latest msgId/id）
- [√] 5.2 按策略选择条件切换到通知模式，并沿用按实例分组的批量下发
- [√] 5.3 兜底降级：`onlineUserCount >= notifyMaxOnlineUser` 时不推通知
- [√] 5.4 超大群兜底：`groupSize >= hugeGroupNoNotifySize` 时跳过路由批查，直接不推通知

## 6. 验证与测试
- [√] 6.1 单元测试：策略选择逻辑（阈值/兜底分支）
- [√] 6.2 集成测试：模拟多实例路由（伪造 `im:gw:route:*`）验证按 `serverId` 分组与批量下发
- [-] 6.3 性能冒烟：构造 2k/10k 群成员，验证每条消息 Redis 调用次数与耗时趋势（需要你本地压测/脚本验证）

## 7. 安全检查与回滚
- [√] 7.1 安全检查：避免在 eventLoop 执行阻塞 IO；避免一次性构造超大 List 导致 OOM
- [√] 7.2 回滚开关：通过配置将策略强制固定为策略1/策略2/不通知（必要时）
