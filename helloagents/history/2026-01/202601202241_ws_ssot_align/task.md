# 轻量迭代：WS 投递 SSOT（一页纸）+ 历史注释对齐

任务清单：
- [√] 新增“一页纸”文档：明确 DB/Redis SSOT 边界与投递口径
- [√] 对齐 `AckType` 枚举注释与真实取值（1=SAVED,2=DELIVERED,3=READ）
- [√] 对齐 `MessageStatus` 枚举注释（补齐 5=RECEIVED,6=DROPPED）
- [√] 修正 `schema-mysql.sql` 的 `t_message_ack.ack_type` 注释
- [√] 新增 Flyway 迁移修正 `t_message_ack.ack_type` 注释（不修改 V1）
- [√] 更新 domain 模块文档：明确“送达/已读 SSOT=成员游标”，`t_message_ack` 非 SSOT
- [√] 更新概览链接与 CHANGELOG
- [√] 验证：`mvn test`
