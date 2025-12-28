# 数据模型

## 数据库

初始化脚本：`src/main/resources/db/schema-mysql.sql`

当前表（从 schema 提取）：
- `t_user`
- `t_single_chat`
- `t_single_chat_member`
- `t_group`
- `t_group_member`
- `t_message`
- `t_message_ack`
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

## 领域实体与映射

- 实体：`com.miniim.domain.entity.*`
- Mapper：`com.miniim.domain.mapper.*`
- Service：`com.miniim.domain.service.*`

MyBatis-Plus 相关配置：`com.miniim.config.MybatisPlusConfig`、`com.miniim.config.MyMetaObjectHandler`
