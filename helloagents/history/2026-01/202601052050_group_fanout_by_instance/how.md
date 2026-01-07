# 技术设计: 群聊大群优化（按实例分组批量 PUSH）

## 技术方案

### 核心技术
- Redis 批量读取：`MGET im:gw:route:{userId}` 获取在线路由（离线无 key 即跳过）。
- 路由 SSOT：复用 `WsRouteStore` 作为在线路由来源（值为 `serverId|connId`）。
- 控制面：复用 Redis Pub/Sub（`im:gw:ctrl:{serverId}`）作为跨实例下发通道。
- 本机写出：目标实例收到批量 PUSH 后，本机基于 `SessionRegistry` 找到在线 channel 并用 `WsWriter` 写出。

### 实现要点
1. 批量 PUSH 消息模型（向后兼容）
   - 在 `WsClusterMessage` 增加 `userIds` 字段（List<Long>）。
   - 约定：
     - `userIds` 非空：批量推送（忽略 `userId`）。
     - 否则：保持旧逻辑（单用户推送 `userId`）。
2. 发布端 API
   - `WsClusterBus` 增加 `publishPushBatch(serverId, userIds, envelope)`。
   - 发送端按 `serverId` 分组，并按 `500` 切片多次 publish。
3. 订阅端落地
   - `WsClusterListener` 对 `PUSH`：
     - 批量：遍历 `userIds`，对每个 uid 的本机 channels 写出。
     - 单用户：保持现有逻辑。
4. 群聊下发（逻辑扇出）
   - 在 `WsGroupChatHandler` 的异步链路（`imDbExecutor`）内：
     1) 获取群成员ID集合（优先 Redis Set 缓存；miss 回读 DB 并回填）。
     2) 计算 `importantTargets`（@我/回复我）。
     3) 构造 routeKeys 并 `MGET` 批量取路由。
     4) 按 `serverId` 分组在线成员，并对每个 serverId 拆分 `important/normal` 两桶。
     5) 每桶按 500 切片：
        - 本机 `serverId == self`：本机直接写（`SessionRegistry` + `WsWriter`）。
        - 其他 `serverId`：`publishPushBatch` 下发到目标实例。
5. 并发与线程模型
   - 任何 Redis/DB 操作都在 `imDbExecutor`（或专用 fanout executor）中执行，避免阻塞 Netty `eventLoop`。
   - 写回发送者 ACK/ERROR 与本机写出均通过 `WsWriter` 保证回到目标 channel 的 `eventLoop`。

## 架构决策 ADR

### ADR-001: 群聊从按用户扇出升级为按实例扇出
**上下文:** 大群下发在“按用户逐个 push”模式下，Redis 交互与跨实例 publish 次数按在线人数线性增长。
**决策:** 使用“路由批量读取 + serverId 分组 + 批量 PUSH”将跨实例下发从 O(n) 降为 O(实例数×批次数)。
**理由:** 与现有 `WsRouteStore`/PubSub 结构兼容，改动集中且收益显著，保留离线拉取兜底模型。
**替代方案:** 读扩散（只通知不下发，客户端拉取）→ 拒绝原因: 改动更大，且与当前“实时 best-effort push”产品体验不一致；可作为更大群（万人+）的后续演进方向。
**影响:** Pub/Sub 单条 payload 变大，需要控制批大小与限速。

## 安全与性能
- **安全:** 不新增敏感数据存储；批量 PUSH payload 仅包含 `userIds` 与已脱敏的消息 envelope。
- **性能:** 降低 Redis/跨实例消息次数；本机写出仍为 per-user，但避免跨实例 per-user publish。
- **降级:** Redis MGET 失败时可退化为“本机直推 + 逐个 `pushToUser`”或“仅落库不推送”，由配置与 fail-open 策略决定。

## 测试与部署
- **测试:**
  - 单元测试：`serverId` 分组与 `important/normal` 分流切片正确性；批量 PUSH 兼容单用户 PUSH。
  - 手工测试：启动两实例，构造群成员分布在不同实例，验证 publish 次数显著下降且消息可达。
- **部署:** 先灰度开启群聊批量 PUSH（仅 GROUP_CHAT），观察 Redis 命中率、publish 次数、下发延迟与失败率。

