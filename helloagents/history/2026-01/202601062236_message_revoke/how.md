# 技术设计: 消息撤回（2分钟，仅发送者）

## 技术方案

### 核心技术
- 后端：Spring Boot + MyBatis-Plus + Netty WebSocket
- 前端：Vite + Vue3 + TS（以现有实现为准）

### 总体策略（方案1：视图脱敏 + WS 撤回事件）
1. 撤回在服务端仅推进状态：`t_message.status -> REVOKED`，不修改 `content`（原文保留）
2. 对外输出统一做“撤回脱敏”：
   - HTTP 历史/会话 lastMessage：`content` 固定返回“已撤回”
   - WS 实时投递/离线补发：`body` 固定下发“已撤回”
3. 新增 WS 消息类型：
   - 客户端 → 服务端：`type="MESSAGE_REVOKE"`（携带目标 `serverMsgId`）
   - 服务端 → 客户端：`type="MESSAGE_REVOKED"`（广播给对端/群成员；必要字段用于定位消息）
4. 发送者确认：服务端对发送者回 `ACK`（`ackType="revoked"`）或同样下发 `MESSAGE_REVOKED`（实现阶段二选一，优先保持前端改动最小）

## 实现要点

### 1) WS 协议与路由
- 在 `WsFrameHandler` 增加 `case "MESSAGE_REVOKE"` 分发到新 handler（建议新增 `WsMessageRevokeHandler`，避免让 `WsSingleChatHandler/WsGroupChatHandler` 继续膨胀）
- `WsEnvelope` 可复用现有字段：
  - 撤回请求：`serverMsgId` 表示“要撤回的目标消息 id”
  - 撤回通知：`serverMsgId` 同样表示目标消息 id，`from` 为撤回者；补充 `to`（单聊对端）或 `groupId`（群聊）

### 2) 业务校验（仅发送者，2分钟）
撤回请求处理流程：
1. 鉴权：从 channel 绑定的 `userId` 获取当前用户
2. 参数校验：`serverMsgId` 必填且可解析为正数（兼容 `id`/`server_msg_id` 的双字段查找策略可按现状选择）
3. 查消息并校验：
   - 消息存在
   - `fromUserId == currentUserId`（仅发送者）
   - `status != REVOKED`（支持幂等）
   - `now - createdAt <= 2 minutes`（以服务端时间为准）
   - 群聊：确认消息属于群；必要时补充“当前仍为群成员”的约束（实现阶段最终确定）
4. 状态更新：仅更新 `status` 与 `updatedAt`（建议用条件更新保证并发下幂等：`where id=? and status != REVOKED`）
5. 会话时间推进：
   - 单聊：更新 `t_single_chat.updated_at=now`（用于会话列表排序/刷新）
   - 群聊：更新 `t_group.updated_at=now`（与现有会话 cursor 逻辑一致）

### 3) 通知对端（单端在线）
- 单聊：推送给对端（`WsPushService.pushToUser(peerUserId, envelope)`）
- 群聊：推送给在线群成员（复用 `GroupMemberIdsCache` 或查 DB），并排除撤回者本人是否推送可按前端实现决定（推荐：也推送给自己，保证多 tab/本地状态一致）
- 多实例：`WsPushService` 已支持跨实例路由（基于 `WsRouteStore` + cluster bus），撤回通知沿用即可

### 4) 对外内容脱敏（关键一致性点）

目标：**服务端保留原文**，但通过所有对外出口（HTTP/WS）统一输出“已撤回”，避免信息泄露与展示不一致。

推荐实现路径（实现阶段二选一，以改动最小为准）：
- **路径A（推荐）：DTO 输出替换**
  - HTTP Controller 不再直接返回 `MessageEntity`，改为返回 `MessageViewDto`（字段对齐，`content` 由服务端计算：`status==REVOKED ? "已撤回" : 原文`）
  - 会话 lastMessage 同理输出 DTO（避免 lastMessage 泄露原文）
- **路径B：序列化层统一脱敏**
  - 为 `MessageEntity` 的 `content` 输出提供 Jackson 自定义序列化（仅影响 JSON 输出，不影响 DB）
  - 注意：避免影响 MyBatis 取值；不要改写实体 getter 以免污染落库逻辑

WS 输出脱敏点：
- 实时撤回事件 `MESSAGE_REVOKED`：不携带原文（只携带定位字段与必要上下文）
- 离线补发 `WsResendService.writePending`：若消息 `status==REVOKED`，则 `body="已撤回"` 且不下发原文

### 5) 错误处理与原因码（WS `ERROR.reason`）
- `missing_server_msg_id`：未传 `serverMsgId`
- `bad_server_msg_id`：`serverMsgId` 非法
- `message_not_found`：目标消息不存在
- `not_message_sender`：非发送者
- `revoke_timeout`：超过 2 分钟窗口
- `not_group_member`：群聊消息撤回但当前用户无权限（如需约束“仍为群成员”）
- `internal_error`：服务端内部错误

## API设计

### WS: 撤回请求
`type="MESSAGE_REVOKE"`
- `serverMsgId`: 要撤回的目标消息 id（字符串）
- `clientMsgId`: 可选（用于前端做请求幂等/状态关联，是否必填以实现阶段为准）

### WS: 撤回通知（广播）
`type="MESSAGE_REVOKED"`
- `serverMsgId`: 被撤回的目标消息 id（字符串）
- `from`: 撤回者 userId
- `to`: 单聊对端 userId（单聊场景）
- `groupId`: 群 id（群聊场景）
- `ts`: 服务端时间戳（毫秒）

### WS: 发送者确认
两种等价方案（二选一）：
1. 复用 `ACK`：
   - `type="ACK"`, `ackType="revoked"`, `serverMsgId=<目标消息 id>`
2. 直接对发送者也下发 `MESSAGE_REVOKED`（与接收侧一致）

## 安全与性能
- **安全:**
  - 权限控制：强制 `fromUserId == currentUserId`
  - 时限控制：强制 2 分钟窗口（服务端时间）
  - 输入校验：`serverMsgId` 必须为正整数；拒绝空/非法值
  - 输出脱敏：撤回后任何对外出口不返回原文
- **性能:**
  - 撤回处理为“单行更新 + 少量推送”，避免全量扫描
  - 群聊推送：优先复用已有 `GroupMemberIdsCache` 降低 DB 压力

## 测试与部署
- **测试:**
  - WS 冒烟：发送→撤回→对端收到撤回事件
  - HTTP 校验：撤回后 cursor/会话 lastMessage 不返回原文
  - 边界：超时/非发送者/重复撤回幂等
- **部署:**
  - 无 DB 变更；后端发布即可
  - 前端需兼容新 WS 类型（灰度时可让后端在发送者确认上优先使用 `ACK`，避免旧端崩溃）
