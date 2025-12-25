# 模块: domain

## 职责
- 定义 IM 领域模型（会话/群组/消息/ack/用户等）
- 提供 MyBatis-Plus Mapper 与领域 Service

## 结构
- `entity`: Entity 定义（如 User/Message/Group 等）
- `enums`: 领域枚举（消息类型/状态/会话类型等）
- `mapper`: 数据访问层
- `service`: 领域服务层

## 与数据库的对应（以 schema 为准）
- 表定义：`src/main/resources/db/schema-mysql.sql`
- 实体包：`com.miniim.domain.entity`