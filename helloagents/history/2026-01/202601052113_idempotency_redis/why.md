# Redis 幂等强化（ClientMsgId）- 变更提案

## 1. 现状（以代码为准）

项目当前已存在 `ClientMsgIdIdempotency`：
- 本机 L1：Caffeine
- 跨实例 L2：Redis `SETNX + TTL`（key 前缀 `im:idem:client_msg_id:`）
- 用法：单聊 / 群聊 / 好友请求等写入前先 `putIfAbsent`，若命中则直接返回已分配的 `serverMsgId` 并回 ACK，避免重复落库/重复投递。

因此“Redis 幂等”并非 0→1，而是需要在现有实现上做**明确口径、窗口、键设计、异常语义、多实例一致性**的强化与文档化。

## 2. 背景与问题

随着多实例（WS 网关水平扩展）引入，单纯本机 Caffeine 幂等会失效：同一客户端重试可能落到不同实例，导致重复落库。

现状已接入 Redis，但仍有几个可优化点：
- 幂等窗口：当前 Redis TTL 与 Caffeine 过期规则绑定（默认 600s），与预期窗口（你提到的 1800s）不一致。
- 过期语义：当前 Caffeine 使用 `expireAfterAccess`，在重试风暴下可能延长窗口并导致本机缓存热 key 长期驻留。
- 存储格式：目前 Redis value 存 JSON（Claim），可精简为只存 `serverMsgId` 或结构化字段，减少序列化成本。
- 失败语义：Redis 不可用时为 fail-open（降级本机），需要明确在多实例下可能出现重复写库的风险边界。

## 3. 目标与成功标准

### 3.1 目标
- 统一幂等窗口为 `1800s`（可配置）。
- 明确幂等 key 组成与业务前缀（避免不同业务共用 `clientMsgId` 造成碰撞）。
- 在 Redis 可用时，跨实例保证：同一 `(userId + clientMsgId + bizPrefix)` 只生成一个 `serverMsgId`。
- 降低序列化与 Redis 往返开销（可选：Lua 一次完成 set/get）。

### 3.2 成功标准
- 多实例下，客户端重试不会导致重复落库（在 Redis 可用情况下）。
- 发生重复请求时，服务端返回同一个 `serverMsgId`（ACK 可重复，但语义一致）。
- 代码与知识库对齐：文档清晰说明窗口、降级策略与风险。

## 4. 已确认决策
- 幂等 key 维度：`userId`（发送者）为主维度。
- 幂等窗口：`1800s`。

