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

## 群成员状态约定（v1，以代码为准）
- 表：`t_group_member`
- 关键字段：
  - `mute_until`：免打扰（仅屏蔽通知，不影响收发）
  - `speak_mute_until`：禁言（发言限制；禁止发送群消息）

## 单聊消息状态约定（v1，以代码为准）
- 表：`t_message`
- 关键字段：
  - `id`：msgId（建议作为 `serverMsgId` 的主键来源）
  - `client_msg_id`：客户端幂等键（用于发送方重试去重）
  - `status`：消息状态（枚举见 `com.miniim.domain.enums.MessageStatus`）
- 关键口径（以当前实现为准）：
  - `t_message.status` 主要用于表示消息是否**已落库/已撤回**等“消息本体状态”（如 `SAVED/REVOKED`）。
  - **送达/已读的最终态不以 `t_message.status` 为准**，以成员游标为准（见下文）。

## 送达 / 已读（SSOT：成员游标）
- 单聊表：`t_single_chat_member`
  - `last_delivered_msg_id`：该成员已投递到的最大 msgId（单调递增）
  - `last_read_msg_id`：该成员已读到的最大 msgId（单调递增）
- 群聊表：`t_group_member`
  - `last_delivered_msg_id / last_read_msg_id`：语义同上

## ACK 明细表（弃用/保留，非 SSOT）
- 表：`t_message_ack`
- 说明：本项目当前不写入该表（弃用业务写入），仅保留结构；送达/已读 SSOT 仍以成员游标（cursor）为准。
- 备注：如未来确需“逐消息逐设备”的审计流水，再考虑启用为事件日志（仍非 SSOT），避免与 cursor 口径冲突。

## 通话记录（Phase1）
- 表：`t_call_record`
- 用途：记录单聊 WebRTC 通话的状态流转（ringing/accepted/rejected/canceled/ended/missed/failed）与失败原因/时长。
- 关键代码（以代码为准）：
  - Entity：`com.miniim.domain.entity.CallRecordEntity`
  - Enum：`com.miniim.domain.enums.CallStatus`
  - Service：`com.miniim.domain.service.CallRecordService`
  - Controller：`com.miniim.domain.controller.CallRecordController`
