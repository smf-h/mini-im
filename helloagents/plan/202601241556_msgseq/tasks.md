# 任务清单：msgSeq 落地（会话内序列 + cursor 迁移）

> 说明：本清单以“直接切换 msgSeq 作为 cursor”为目标，旧 `*_msg_id` 字段暂留但不再参与主链路。

## A. 数据库迁移（Flyway）

- [ ] 新增迁移 `V8__msgseq_cursor.sql`
  - [ ] `t_message` 增加 `msg_seq` + 索引/唯一约束
  - [ ] `t_single_chat` / `t_group` 增加 `next_msg_seq`
  - [ ] `t_single_chat_member` / `t_group_member` 增加 `last_delivered_msg_seq` / `last_read_msg_seq`
- [ ] 历史数据回填
  - [ ] 按会话回填 `t_message.msg_seq`
  - [ ] 回填 `t_single_chat.next_msg_seq` / `t_group.next_msg_seq`
  - [ ] 将成员游标从 `*_msg_id` 映射到 `*_msg_seq`

## B. 服务端：写入链路（WS 发送）

- [ ] `MessageEntity` 增加字段 `msgSeq`（映射到 `t_message.msg_seq`）
- [ ] 新增 “分配 msgSeq” 的 mapper/service
  - [ ] `SingleChatMapper.allocateNextMsgSeq(singleChatId)`
  - [ ] `GroupMapper.allocateNextMsgSeq(groupId)`
- [ ] `WsSingleChatHandler`：保存消息前分配并写入 `msgSeq`
- [ ] `WsGroupChatHandler`：保存消息前分配并写入 `msgSeq`

## C. 服务端：ACK / cursor / 补发 / 未读

- [ ] ACK 推进游标改为写 `*_msg_seq`
  - [ ] `SingleChatMemberMapper` 新增 `markDeliveredSeq/markReadSeq`
  - [ ] `GroupMemberMapper` 新增 `markDeliveredSeq/markReadSeq`
  - [ ] `WsAckHandler`：用 `entity.msgSeq` 推进游标
- [ ] 离线补发改为按 seq 区间
  - [ ] `MessageMapper.selectPendingSingleChatMessagesForUser`：`msg_seq > last_delivered_msg_seq`
  - [ ] `MessageMapper.selectPendingGroupMessagesForUser`：同上
  - [ ] `WsResendService`：排序字段与日志字段核对
- [ ] 未读数统计改为按 seq
  - [ ] `MessageMapper.selectUnreadCountsForUser`：`msg_seq > last_read_msg_seq`
  - [ ] `MessageMapper.selectGroupUnreadCountsForUser`：同上
  - [ ] `MessageMentionMapper.selectMentionUnreadCountsForUser`：改为 join `t_message` 用 `msg_seq`（或冗余写入，二选一，优先 join）
- [ ] 会话列表最后一条消息查询改为按 `max(msg_seq)`
  - [ ] `MessageMapper.selectLastMessagesBySingleChatIds`
  - [ ] `MessageMapper.selectLastMessagesByGroupIds`

## D. HTTP API：分页游标口径迁移

- [ ] `/single-chat/message/cursor`：由 `lastId` 切为 `lastSeq`
- [ ] `/group/message/cursor`：由 `lastId` 切为 `lastSeq`
- [ ] `/group/message/since`：由 `sinceId` 切为 `sinceSeq`
- [ ] `MessageServiceImpl`：cursor/since 查询条件与排序字段改为 `msgSeq`

## E. 前端（网页端）

- [ ] 拉取消息分页参数改为 seq（对应 D）
- [ ] 本地消息排序优先 `msgSeq`（若服务端下发/HTTP 返回包含该字段）
- [ ] 去重仍以 `serverMsgId` 为准（保持 cursor 模型前提）
- [ ] ACK 推进只按“连续前缀 seq”（禁止跳跃 ACK）；发现 gap 时走 HTTP `sinceSeq` 或触发补发补齐

## F. 文档与验收

- [ ] 更新知识库口径
  - [ ] `helloagents/wiki/ws_delivery_ssot_onepager.md`：补充 `msgSeq` 与 `serverMsgId` 分工、cursor 字段切换说明
  - [ ] `helloagents/wiki/api.md`：更新消息分页接口参数与返回字段
- [ ] 测试
  - [ ] `mvn test` 通过
  - [ ] 最小联调：发送→SAVED→收到→delivered/read→未读归零→断线重连补发不漏
  - [ ] 乱序/缺口用例：先到高 seq 再到低 seq 时，不跳 ACK，补齐后 cursor 才前进
