# 技术设计：小群群聊 + 重要消息（@我/回复我）

## 1. 数据模型

### 1.1 消息本体（复用）
- `t_message`：群消息只存一份（`chat_type=2, group_id, id/msgId, from_user_id, content, ...`）
- `t_group_member`：成员位点（`last_delivered_msg_id / last_read_msg_id`）用于未读与补发。

### 1.2 稀疏索引表（新增）
新增 `t_message_mention`（稀疏写入，仅对被影响的用户写一行）：
- `group_id`
- `message_id`（引用 `t_message.id`）
- `mentioned_user_id`
- `mention_type`（MENTION/REPLY/AT_ALL）
- `created_at`

查询 `@未读`：`mentioned_user_id = me AND message_id > t_group_member.last_read_msg_id`。

> 约束：本期 `@all` 不做强提醒，因此不计入 `@未读`；可选择不落库或仅落库用于将来扩展。

## 2. WS 协议

### 2.1 客户端发送（GROUP_CHAT）
字段：
- `type=GROUP_CHAT`
- `groupId`
- `clientMsgId`
- `body`
- `mentions`（可选，用户 id 列表，来自轻量解析 `@123`）
- `replyToServerMsgId`（可选，引用/回复目标消息）

### 2.2 服务端下发（GROUP_CHAT）
字段：
- `type=GROUP_CHAT`
- `groupId`
- `serverMsgId`
- `from`
- `body`
- `ts`
- `important`（对当前接收方是否为重要消息：@我/回复我）

## 3. 后端链路
- 校验：鉴权、群成员身份、消息长度、mentions/replyTo 合法性。
- 幂等：`GROUP_CHAT:<groupId>:<clientMsgId>` 作为 idempotency key。
- 落库：保存 `t_message`；按 mentions/replyTo 写入 `t_message_mention`。
- 推送：对群成员在线连接推送（对每个接收方计算 `important`）。
- 补发：复用现有 `selectPendingGroupMessagesForUser`，并在推送 pending 时补齐 `important`。

## 4. 前端链路
- 群列表页：展示群会话，包含未读数 + @未读数。
- 群聊天页：发送/接收 `GROUP_CHAT`，并在可见且吸底时发送 `ACK(delivered/read)` 推进游标。
- 通知：收到 `GROUP_CHAT` 且 `important=true` 且不在对应群页时 toast 提醒，点击跳转群页。

