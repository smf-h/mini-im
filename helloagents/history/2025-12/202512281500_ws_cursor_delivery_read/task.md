# 任务清单（开发实施）

- [√] 新增单聊成员表 `t_single_chat_member`
- [√] 单聊发送落库时补齐 member 行（双向）
- [√] `ACK(delivered/read)` 推进成员游标（兼容 `ack_receive/received/ack_read`）
- [√] `AUTH` 后按游标补发未投递区间
- [√] 可选兜底补发定时任务（默认关闭）
- [√] 同步更新知识库（API/数据模型/Changelog）
- [√] 质量验证：`mvn -DskipTests package`
