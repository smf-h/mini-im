# 数据模型

## 数据库

初始化脚本：`src/main/resources/db/schema-mysql.sql`

当前表（从 schema 提取）：
- `t_user`
- `t_single_chat`
- `t_group`
- `t_group_member`
- `t_message`
- `t_message_ack`

### t_message 查询习惯（单聊）

- 推荐用 `single_chat_id + id` 做游标分页：`where single_chat_id = ? and id < lastId order by id desc limit ?`
- schema 已包含组合索引：`idx_msg_single_id_id (single_chat_id, id)`（适合上述查询）

---

## 领域实体与映射

- 实体：`com.miniim.domain.entity.*`
- Mapper：`com.miniim.domain.mapper.*`
- Service：`com.miniim.domain.service.*`

MyBatis-Plus 相关配置：`com.miniim.config.MybatisPlusConfig`、`com.miniim.config.MyMetaObjectHandler`
