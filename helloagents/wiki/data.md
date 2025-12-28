# 数据模型

## 数据库

初始化脚本：`src/main/resources/db/schema-mysql.sql`

当前表（从 schema 提取）：
- `t_user`
- `t_single_chat`
- `t_single_chat_member`
- `t_group`
- `t_group_member`
- `t_group_join_request`
- `t_message`
- `t_message_ack`
- `t_message_mention`
- `t_friend_relation`
- `t_friend_request`

### t_message 查询习惯（单聊）

- 推荐用 `single_chat_id + id` 做游标分页：`where single_chat_id = ? and id < lastId order by id desc limit ?`
- schema 已包含组合索引：`idx_msg_single_id_id (single_chat_id, id)`（适合上述查询）

### t_friend_request 查询习惯（好友申请列表）

- inbox（我收到）：`where to_user_id=? and id < lastId order by id desc limit ?`
- outbox（我发出）：`where from_user_id=? and id < lastId order by id desc limit ?`
- all（收+发）：`where (to_user_id=? or from_user_id=?) and id < lastId order by id desc limit ?`

schema 已包含常用索引：
- `idx_friend_request_to_status_time (to_user_id, status, created_at)`
- `idx_friend_request_from_time (from_user_id, created_at)`
- `idx_friend_request_from_to_status (from_user_id, to_user_id, status)`

---

## 送达/已读游标（方案B）

- 单聊：`t_single_chat_member`（`single_chat_id + user_id` 唯一）保存每个用户在该会话的 `last_delivered_msg_id / last_read_msg_id`。
- 群聊：`t_group_member` 保存每个成员在该群的 `last_delivered_msg_id / last_read_msg_id`。
- 服务端在用户 `AUTH` 后按游标补发：拉取 `id > last_delivered_msg_id` 的未投递区间下发给该用户。

---

## 群聊重要消息稀疏索引（@我 / 回复我）

表：`t_message_mention`

设计目标：在群聊里，“重要消息”是稀疏事件（不是每条都 @/回复），因此只对被影响用户落库一行，用于离线补拉、会话列表统计与 toast 提醒。

核心字段：
- `group_id`：群 id
- `message_id`：群消息 id（指向 `t_message.id`）
- `mentioned_user_id`：被 @/被回复的用户 id
- `mention_type`：`MENTION`（@我）/ `REPLY`（回复我）

主要查询口径：
- 会话列表的 `mentionUnreadCount`：对每个群取成员 `last_read_msg_id`，查询 `t_message_mention` 中 `message_id > last_read_msg_id` 的命中数量
- 离线补发“重要标记”：补发群消息时，按用户查询命中的 `message_id` 集合，将对应消息在 WS 下发时标记 `important=true`

索引（写在 schema / migration 中）：
- `idx_mm_user_group_msg (mentioned_user_id, group_id, message_id)`：按用户+群统计/拉取命中
- `idx_mm_group_msg (group_id, message_id)`：按群回溯/清理

---

## FriendCode / GroupCode（按码加好友 / 申请入群）

### t_user.friend_code

用途：给用户一个“不可枚举”的公开加好友码（替代直接输入 `uid`）。
- `friend_code`：字符串码（唯一，可为空）
- `friend_code_updated_at`：最近一次生成/重置时间（用于限频）

### t_group.group_code

用途：给群一个“不可枚举”的公开入群码（用于申请入群）。
- `group_code`：字符串码（唯一，可为空）
- `group_code_updated_at`：最近一次生成/重置时间（用于限频）

---

## 入群申请（审批）

表：`t_group_join_request`

用途：实现 `1C` 流程（申请入群 → 群主/管理员审批 → 申请者收到通知/可拉取结果）。

关键字段：
- `group_id`：群 id
- `from_user_id`：申请者 userId
- `message`：验证信息（可空）
- `status`：`PENDING/ACCEPTED/REJECTED/CANCELED`
- `handled_by/handled_at`：处理人/处理时间

查询口径：
- 管理员处理队列：`where group_id=? and status=PENDING and id < lastId order by id desc limit ?`
- 申请者历史：`where from_user_id=? and status in (...) order by id desc limit ?`

## 领域实体与映射

- 实体：`com.miniim.domain.entity.*`
- Mapper：`com.miniim.domain.mapper.*`
- Service：`com.miniim.domain.service.*`

MyBatis-Plus 相关配置：`com.miniim.config.MybatisPlusConfig`、`com.miniim.config.MyMetaObjectHandler`
