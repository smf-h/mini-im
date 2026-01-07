# Redis 幂等强化（ClientMsgId）- 技术设计

## 1. Key 设计

建议统一为：
- Redis key：`im:idem:client_msg_id:{userId}:{biz}:{clientMsgId}`

其中 `biz` 由调用方显式传入，用于隔离不同业务：
- 单聊：`SINGLE_CHAT`
- 群聊：`GROUP_CHAT:{groupId}`（避免同一 clientMsgId 在不同群复用时碰撞）
- 好友申请：`FRIEND_REQUEST`
- 其它写接口按需扩展

说明：当前代码已有 “GROUP_CHAT:{groupId}:clientMsgId” 的业务前缀拼接，但整体格式尚未统一到 Redis key 层；本方案把 `biz` 作为结构化段落，便于排查与运营。

## 2. 存储模型

### 2.1 推荐（最小改动）
- value 仅存 `serverMsgId`（字符串）
- Redis 写：`SET key serverMsgId NX EX 1800`
- 命中：`GET key` 返回已存在的 `serverMsgId`

### 2.2 可选增强（减少一次往返）
- Lua 脚本：入参 `key, value, ttl`，返回：
  - `nil` 表示写入成功（首次）
  - `existedValue` 表示已存在（重复）

> 选择 Lua 的前提是：项目允许引入脚本文件或内联脚本，并对运维可观测性无额外要求。

## 3. 本机缓存（Caffeine）策略

为了与“幂等窗口”语义一致，建议：
- 本机过期改为 `expireAfterWrite(1800s)`（或独立配置）
- 本机只做 L1 缓存命中加速，不影响幂等窗口边界

保留 fail-open：
- Redis 异常/超时：降级 `cache.asMap().putIfAbsent`
- 但需在文档中明确：多实例下 Redis 不可用时**可能发生重复落库**，这是可接受的降级边界（可配开关切换为 fail-close）。

## 4. 调用点改造

对所有“会落库/产生业务副作用”的 WS 写入类消息：
1) 生成 `serverMsgId`
2) 先 claim（幂等占位）
3) 再执行落库/投递
4) 落库失败：释放 claim（当前已有 remove）

需要同步覆盖：
- 单聊发送
- 群聊发送
- 好友申请（已覆盖，但 key 结构统一）
- 未来拆出的 `auth / write / writeAck / writeError` 等 handler（按业务副作用判定）

## 5. 配置项建议

新增或调整配置（保持向后兼容）：
- `im.idempotency.client-msg-id.enabled`（默认 true）
- `im.idempotency.client-msg-id.ttl-seconds`（默认 1800）
- `im.idempotency.client-msg-id.local-cache.enabled`（默认 true）
- `im.idempotency.client-msg-id.local-cache.maximum-size`（默认沿用现有）

兼容策略：
- 现有 `im.caffeine.client-msg-id.*` 保留；新配置存在时优先使用新配置。

## 6. 测试策略

- 单元测试：Lua/非 Lua 分支下的 `putIfAbsent` 行为（首次 / 重复 / remove）
- 集成测试：嵌入式 Redis 或 Testcontainers（若项目已有），验证多实例语义可用性（至少模拟两次调用共享 Redis）
- 压测冒烟：高频重复 clientMsgId 下，确认不产生重复写库（Redis 可用时）

