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

## 单聊消息状态约定（v1，以代码为准）
- 表：`t_message`
- 关键字段：
  - `id`：msgId（建议作为 `serverMsgId` 的主键来源）
  - `client_msg_id`：客户端幂等键（用于发送方重试去重）
  - `status`：消息状态（枚举见 `com.miniim.domain.enums.MessageStatus`）
- 关键状态（当前使用口径）：
  - `SAVED`：已落库，等待接收方 ACK_RECEIVED
  - `DROPPED`：离线/待补发
  - `RECEIVED`：接收方已收到（业务最终态之一）

## ACK 存储（规划）
- 表：`t_message_ack`
- 用途：存储客户端回执（DELIVERED/READ 等），用于更精细的投递确认与未读统计（后续迭代）。

## 通话记录（Phase1）
- 表：`t_call_record`
- 用途：记录单聊 WebRTC 通话的状态流转（ringing/accepted/rejected/canceled/ended/missed/failed）与失败原因/时长。
- 关键代码（以代码为准）：
  - Entity：`com.miniim.domain.entity.CallRecordEntity`
  - Enum：`com.miniim.domain.enums.CallStatus`
  - Service：`com.miniim.domain.service.CallRecordService`
  - Controller：`com.miniim.domain.controller.CallRecordController`
