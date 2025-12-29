# 怎么做（How）

## 1) WebSocket 协议（好友申请 v1）

### 客户端 → 服务端：发起好友申请

`type = "FRIEND_REQUEST"`

必填：
- `clientMsgId`：客户端幂等键（重发必须保持不变）
- `to`：接收方 userId
- `body`：申请验证信息（≤256）

服务端行为：
- 校验：已鉴权、to 合法且不等于自己、body 长度
- 幂等：`key = fromUserId + "-" + "FRIEND_REQUEST:" + clientMsgId`（Caffeine）
- 落库成功：回 `ACK(saved)` 给发送方（serverMsgId=requestId）
- best-effort 推送：如果接收方在线，向其发送一次 `FRIEND_REQUEST` 通知（包含 serverMsgId/from/to/body）

### 服务端 → 发送方：ACK

`type = "ACK"`, `ackType = "saved"`, 回传：
- `clientMsgId`
- `serverMsgId`（作为 friendRequestId）

## 2) HTTP 列表接口

统一前缀：`/friend/request`

- inbox（我收到）：`to_user_id = me`
- outbox（我发出）：`from_user_id = me`
- all（收+发）：`from_user_id = me OR to_user_id = me`

游标分页：
- `/cursor?box=inbox|outbox|all&limit&lastId`

普通分页（可选但提供，便于管理后台/调试）：
- `/list?box=inbox|outbox|all&pageNo&pageSize`

排序与游标规则：
- 按 `id DESC` 返回
- `lastId` 表示“下一页从 `id < lastId` 开始”

## 3) 前端联调页面（前后端分离）

提供 `frontend/`：
- 登录（HTTP /auth/login）
- 刷新（HTTP /auth/refresh，可选）
- 建立 WS（使用 query `?token=`，因为浏览器 WS 无法自定义 Authorization header）
- 发起好友申请（WS 发送 `FRIEND_REQUEST`，自动生成 clientMsgId）
- 列表展示（HTTP cursor：inbox/outbox/all）

后端增加 CORS（仅允许 localhost/127.0.0.1 端口范围），便于前端 dev server 调用 HTTP。

## 4) 验证

- `mvn -DskipTests package`
- 运行服务端后：
  - 打开 `frontend/index.html`（通过本地静态服务）
  - 登录 -> WS 连接 -> 发申请 -> inbox/outbox 游标列表验证
