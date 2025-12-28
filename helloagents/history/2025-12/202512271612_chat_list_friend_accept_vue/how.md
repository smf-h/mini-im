# 怎么做（How）

## 1) 单聊会话列表（HTTP）

### 数据来源
- 会话表：`t_single_chat`
- 最后一条消息：`t_message`（按 `single_chat_id` 分组取最大 `id`）
- 会话更新时间：使用 `t_single_chat.updated_at`，在“新消息落库”时对对应 `single_chat_id` 执行 touch 更新。

### API

前缀：`/single-chat/conversation`

- `GET /cursor?limit=20&lastUpdatedAt=...&lastId=...`
  - 语义：按 `updatedAt desc, id desc` 游标分页
- `GET /list?pageNo=1&pageSize=20`
  - 语义：普通分页

返回 DTO（核心字段）：
- `singleChatId`
- `peerUserId`
- `updatedAt`
- `lastMessage`（可空：含 `serverMsgId/content/fromUserId/toUserId/createdAt`）

## 2) 好友申请处理（HTTP）

前缀：`/friend/request`

- `POST /accept`：同意好友申请
  - 校验：当前用户必须是 `toUserId`，且状态为 `PENDING`
  - 事务：更新 `t_friend_request` -> 插入 `t_friend_relation`（幂等）-> 创建 `t_single_chat`（幂等）
  - 返回：`singleChatId`
- `POST /reject`：拒绝好友申请

## 3) 前端（Vue 用户站点）

目标：前后端分离，面向用户可用。

页面：
- 登录页
- 会话列表页（显示最后消息、更新时间排序）
- 聊天页（WS 实时收发，HTTP 游标拉取历史）
- 好友申请页（收/发列表、同意/拒绝、发起申请）

WebSocket：
- 连接：`ws://host:9001/ws?token=<accessToken>`（浏览器限制不支持自定义 header）
- 发送：`SINGLE_CHAT` / `FRIEND_REQUEST`
- 接收：`SINGLE_CHAT` / `FRIEND_REQUEST` / `ACK` / `ERROR`
- 收到 `SINGLE_CHAT` 后自动回 `ACK(ack_receive)`

## 4) 测试

- 修复 `scripts/ws-smoke-test` friend_request 场景的 HTTP 断言逻辑（避免因为 body 转义导致误判）
- `mvn -DskipTests package` 编译验证

