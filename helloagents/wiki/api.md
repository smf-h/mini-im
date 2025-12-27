# API 手册

## HTTP API

### Auth
- `POST /auth/login`
- `POST /auth/refresh`
- `POST /auth/verify`

实现位置：`com.miniim.auth.web.AuthController`

### Single Chat Messages（单聊消息查询）
- `GET /single-chat/message/cursor?peerUserId=xxx&limit=20&lastId=yyy`
  - 语义：按 `id` 倒序，返回 `id < lastId` 的下一页；`lastId` 为空表示从最新开始
- `GET /single-chat/message/list?peerUserId=xxx&pageNo=1&pageSize=20`
  - 语义：普通分页（返回 MyBatis-Plus `Page`）

实现位置：`com.miniim.domain.controller.SingleChatMessageController`

---

## WebSocket

- 服务端入口：`com.miniim.gateway.ws.NettyWsServer`
- 握手鉴权：`com.miniim.gateway.ws.WsHandshakeAuthHandler`
- 帧处理：`com.miniim.gateway.ws.WsFrameHandler`
- 消息封装：`com.miniim.gateway.ws.WsEnvelope`

具体监听地址/路径等以 `src/main/resources/application-gateway.yml` 配置为准。

---

## WebSocket 业务协议约定（单聊 v1）

### 目标
- 单聊/群聊/好友申请等实时链路统一走 WebSocket；HTTP 仅用于“列表展示/查询类”接口。

### 消息类型
- `AUTH`：首包鉴权（兼容旧客户端）。
- `SINGLE_CHAT`：单聊发送消息（当前仅 TEXT）。
- `ACK`：业务回执（用于幂等确认/接收确认）。
- `ERROR`：错误回包。

### SINGLE_CHAT（客户端 → 服务端）
- 必填字段：
  - `type="SINGLE_CHAT"`
  - `clientMsgId`：客户端幂等键（客户端重发必须保持不变）
  - `to`：接收方 userId
  - `body`：文本内容（TEXT）
- 可选字段：
  - `msgType`：当前建议固定 `TEXT`
  - `ts`：客户端时间戳（仅用于展示/诊断）

### 服务端 ACK（服务端 → 发送方）
- 持久化成功后回：`type="ACK"`
  - `ackType="SAVED"`
  - `clientMsgId` 原样回传
  - `serverMsgId`：服务端生成的消息唯一标识（建议=msgId，客户端去重/展示排序以此为准）

### 服务端投递（服务端 → 接收方）
- 投递 payload 为 `type="SINGLE_CHAT"`，包含：
  - `from`（服务端确认的发送方 userId）
  - `to`（接收方 userId）
  - `clientMsgId`、`serverMsgId`、`body`、`msgType`

### ACK_RECEIVED（接收方 → 服务端）
- 用途：接收方确认“已收到消息”，服务端据此更新数据库消息状态（`RECEIVED`）。
- 推荐字段：
  - `type="ACK"`
  - `ackType="ack_receive"`（兼容：`received`）
  - `serverMsgId`（强烈建议必填）
  - `clientMsgId`（可选，用于额外校验）
  - `to`：原发送方 userId（用于服务端转发 ACK/或校验）

### 幂等与重试（发送方）
- 发送方以“收到 `ACK(SAVED)`”作为服务端落库确认；未收到则客户端按 `clientMsgId` 重发。
- 服务端幂等键：`(fromUserId + '-' + clientMsgId)`；重复请求应返回相同 `serverMsgId`，避免重复落库。

### 离线与补发
- 基本原则：未收到 `ACK_RECEIVED` 视为投递失败；由定时任务兜底重发。
- 状态建议：
  - `SAVED`：已落库待确认
  - `DROPPED`：离线/待补发
  - `RECEIVED`：已收到（业务最终态）

### ⚠️ 当前实现备注（以代码为准）
- 定时补发当前扫描 `status=SAVED` 且 `updatedAt` 超时的消息；投递失败（对端不在线）时会把消息置为 `DROPPED`。
- `ACK_RECEIVED` 的数据库更新逻辑依赖 `clientMsgId/serverMsgId/from/to` 多条件；如客户端字段缺失可能导致 0 行更新（建议长期演进为“以 `serverMsgId` 为主键定位 + to_user_id 校验”）。
