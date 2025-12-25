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

---

## 领域实体与映射

- 实体：`com.miniim.domain.entity.*`
- Mapper：`com.miniim.domain.mapper.*`
- Service：`com.miniim.domain.service.*`

MyBatis-Plus 相关配置：`com.miniim.config.MybatisPlusConfig`、`com.miniim.config.MyMetaObjectHandler`