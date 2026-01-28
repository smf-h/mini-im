# 怎么做（实现要点）

## 数据模型
- 单聊新增：`t_single_chat_member(single_chat_id, user_id, last_delivered_msg_id, last_read_msg_id, ...)`
- 群聊复用：`t_group_member.last_delivered_msg_id / last_read_msg_id`

## WS 协议
- 接收方 → 服务端：`type="ACK"` + `serverMsgId`
  - 送达：`ackType="delivered"`（兼容：`ack_receive` / `received`）
  - 已读：`ackType="read"`（兼容：`ack_read`）
- 服务端行为：
  - 单聊：推进 `t_single_chat_member` 游标
  - 群聊：推进 `t_group_member` 游标

## 补发策略
- 用户 `AUTH` 成功后：按游标拉取待投递区间并下发
- 可选兜底定时补发：默认关闭；配置 `im.cron.resend.enabled=true` 才启用

## 兼容性
- 旧逻辑中依赖 `t_message.status=RECEIVED/DROPPED` 的补发/标记不再作为主路径
