# 为什么要做（方案B：成员游标）

## 背景
`t_message.status` 属于“消息全局状态”，无法表达群聊场景下“每个成员各自的送达/已读进度”。继续在 `t_message` 上维护 `RECEIVED/READ` 会导致：
- 群聊无法准确表示不同成员的阅读进度
- 单聊多端（多设备）也容易出现状态覆盖与抖动

## 目标
- 将“送达/已读/补发”从消息全局维度迁移到成员维度（游标模型）
- AUTH 后按游标补发离线消息，避免依赖定时扫描 `t_message.status`
- 保持对旧客户端 `ack_receive/received` 的兼容

## 成功标准
- 接收方发送 `ACK(delivered/read)` 后，服务端推进对应成员游标
- 用户重连（AUTH）后能收到 `id > last_delivered_msg_id` 的未投递消息
- `mvn -DskipTests package` 编译通过
