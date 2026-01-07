# 变更提案: 群聊大群优化（按实例分组批量 PUSH）

## 需求背景
当前群聊下发路径以“逐成员 push”为主：服务端获取群成员列表后，对每个成员调用 `pushToUser`。当群成员规模上升（千人/万人）时，这种写扩散会放大：
- Redis 路由查询次数（按成员数线性增长）
- 跨实例 Pub/Sub publish 次数（按在线成员数线性增长）
- 单次群发的 CPU/网络开销与尾延迟（成员越多越明显）

本次优化目标是：在保持“best-effort 实时推送 + 离线拉取兜底”的语义下，将群聊下发从“按用户扇出”改为“按实例扇出（逻辑扇出）”，显著降低 Redis 交互与跨实例消息数量。

## 变更内容
1. 群成员获取：继续复用现有“群成员ID集合缓存”（Redis Set，`groupId -> userId`），减少 DB 全量查成员压力。
2. 在线路由批量查询：对群成员批量 `MGET im:gw:route:{userId}` 获取在线路由（离线无路由即跳过）。
3. 按实例分组批量下发：按 `serverId` 分组后，每个实例最多发送少量批量 PUSH（每批最多 500 个 userId）。
4. `important` 分流：对 `@我/回复我` 等 important 收件人与普通收件人拆成两路批量下发，保持现有重要消息语义。
5. 兼容与渐进：控制通道消息结构保持向后兼容（单 user PUSH 仍可用），逐步切换群聊路径到批量 PUSH。

## 影响范围
- **模块:** gateway / cluster
- **文件:** `WsGroupChatHandler` / `WsPushService` / `WsRouteStore` / `WsClusterMessage` / `WsClusterBus` / `WsClusterListener`（以最终实现为准）
- **API:** 无
- **数据:** 无（Redis 仅新增/扩展临时 key 或消息字段）

## 核心场景

### 需求: 群聊-按实例分组下发（逻辑扇出）
**模块:** gateway

#### 场景: 千人群在线分布多实例
群成员 1000 人，其中 400 在线分布在 2 个实例上，其余离线。
- 预期结果: 路由查询为一次批量 `MGET`（不再 per-user get）
- 预期结果: 跨实例 publish 次数约等于“在线实例数 × 2（important/normal）× 批次数”，不再按在线人数线性增长
- 预期结果: 离线成员不参与实时推送，上线通过拉取补齐消息

### 需求: 群聊-important 语义保持
**模块:** gateway

#### 场景: 群内 @我/回复我
发送者 @ 某成员或回复某成员，important 收件人集合与普通收件人集合不同。
- 预期结果: important 收件人收到的消息 envelope 带 `important=true`
- 预期结果: 普通收件人收到的消息 envelope 不带 `important=true`
- 预期结果: 仍支持批量下发（important/normal 分流）

## 风险评估
- **风险:** Pub/Sub 单条消息 payload 变大（携带 userId 列表）。
  - **缓解:** 控制批大小（默认 500），必要时切片并限制并发；未来更大群可切换为“通知 + 拉取（读扩散）”。
- **风险:** 实时推送 best-effort，跨实例 publish 失败可能导致部分在线用户未收到实时推送。
  - **缓解:** 已落库后由“离线/拉取”兜底；同时通过日志与指标观测（必要时重试或降级）。

