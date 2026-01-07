# API 手册

## 数字精度（重要）

- 后端 ID 多为 Java `long/Long`（例如 `userId/id/singleChatId/serverMsgId/requestId`）。浏览器端若按 JS `Number` 解析，超过 `2^53-1` 会发生精度丢失（表现为“四舍五入/末尾变 0”）。
- **协议约定：所有语义为“ID”的 long/Long 字段，JSON 一律输出为字符串**（例如 `"toUserId":"123"`），避免前端误用 Number。
- 非 ID 的 long/Long（例如分页 `total/size/current`）仍保持 JSON 数字类型。
- 客户端建议：凡是语义为“ID/游标”的字段，一律按字符串处理（展示/比较/拼接），不要转 `Number(...)`。

## 微信小程序端（miniprogram）

- 工程目录：`miniprogram/`（原生小程序 + TypeScript，单页容器）
- 配置：`miniprogram/config.ts`（`HTTP_BASE` / `WS_URL`）
- HTTP：`Authorization: Bearer <accessToken>`（遇业务 `code=40100` 或 HTTP 401 自动 refresh 重试一次）
- WS：握手 query `?token=<accessToken>`，连接后发送 `type="AUTH"` 帧；收到 `AUTH_OK` 视为在线

## HTTP API

### Auth
- `POST /auth/login`
- `POST /auth/refresh`
- `POST /auth/verify`

实现位置：`com.miniim.auth.web.AuthController`

补充说明（以代码为准）：
- `POST /auth/login` 目前支持“首次登录自动注册”（用户不存在时会创建用户并设置 `passwordHash`）。
- 如果历史数据存在 `passwordHash` 为空的用户，`/auth/login` 会在同一行补齐 `passwordHash`（不再重复插入，避免 500）。
- 常见失败返回（响应体 `Result.fail(code, msg)` 的 `msg`）：
  - `invalid_username_or_password`（400）
  - `duplicate_key`（400，通常是用户名冲突等唯一键问题）
  - `redis_unavailable`（500，refreshToken 存储依赖 Redis）

### Single Chat Conversations（单聊会话列表）
- `GET /single-chat/conversation/cursor?limit=20&lastUpdatedAt=...&lastId=...`
  - 语义：按 `updatedAt` 倒序（次序：updatedAt desc, id desc），返回下一页游标
  - 约束：`lastUpdatedAt` 与 `lastId` 必须同时为空或同时存在
- `GET /single-chat/conversation/list?pageNo=1&pageSize=20`
  - 语义：普通分页（返回 MyBatis-Plus `Page`）

- 返回补充（以代码为准）：
  - `unreadCount`：当前用户未读条数（基于 `t_single_chat_member.last_read_msg_id` 计算）
  - `peerLastReadMsgId`：对方已读游标（用于“已读/未读”展示）

实现位置：`com.miniim.domain.controller.SingleChatConversationController`

### Single Chat Messages（单聊消息查询）
- `GET /single-chat/message/cursor?peerUserId=xxx&limit=20&lastId=yyy`
  - 语义：按 `id` 倒序，返回 `id < lastId` 的下一页；`lastId` 为空表示从最新开始
- `GET /single-chat/message/list?peerUserId=xxx&pageNo=1&pageSize=20`
  - 语义：普通分页（返回 MyBatis-Plus `Page`）

实现位置：`com.miniim.domain.controller.SingleChatMessageController`

### Friend Relation（好友关系）
- `GET /friend/relation/cursor?limit=10&lastId=yyy`
- `GET /friend/relation/list`

实现位置：`com.miniim.domain.controller.FriendRelationController`

### Friend Request（好友申请列表查询）
- `GET /friend/request/cursor?box=all&limit=20&lastId=yyy`
  - `box`：`inbox`=我收到、`outbox`=我发出、`all`=收+发（默认）
  - 语义：按 `id` 倒序，返回 `id < lastId` 的下一页；`lastId` 为空表示从最新开始
- `GET /friend/request/list?box=all&pageNo=1&pageSize=20`
  - 语义：普通分页（返回 MyBatis-Plus `Page`）
- `POST /friend/request/decide`
  - body：`{ "requestId": 123, "action": "accept|reject" }`
  - 响应：`{ "singleChatId": 123|null }`（accept 时返回会话 id）
- `POST /friend/request/by-code`
  - body：`{ "toFriendCode": "AB12CD34", "message": "..." }`
  - 响应：`{ "requestId": "123", "toUserId": "10002" }`

实现位置：`com.miniim.domain.controller.FriendRequestController`

### User（用户基础信息）
- `GET /user/basic?ids=10001,10002`
  - 用途：前端获取昵称/用户名（例如站内通知、列表展示）
  - 响应：`[{ "id": "10001", "username": "...", "nickname": "..." }, ...]`
- `GET /user/profile?userId=10001`
  - 用途：公开个人主页
  - 响应（示例字段，以代码为准）：`{ "id":"10001", "username":"...", "nickname":"...", "avatarUrl":null, "status":1, "friendCode":"AB12CD34" }`

实现位置：`com.miniim.domain.controller.UserController`
、`com.miniim.domain.controller.MeController`

### Moments（朋友圈，MVP）
- `POST /moment/post/create`
  - body：`{ "content": "..." }`
  - 响应：`{ "postId": "123" }`
- `POST /moment/post/delete`
  - body：`{ "postId": 123 }`
- `GET /moment/feed/cursor?limit=20&lastId=...`
  - 语义：时间线（好友 + 自己），按 `id` 倒序游标分页
  - 返回：`PostDto[]`（以代码为准，包含 `likedByMe/likeCount/commentCount`）
- `GET /moment/user/cursor?userId=10001&limit=20&lastId=...`
  - 语义：指定作者动态（需满足可见性：好友或自己）
- `POST /moment/like/toggle`
  - body：`{ "postId": 123 }`
  - 响应：`{ "liked": true, "likeCount": 1 }`
- `POST /moment/comment/create`
  - body：`{ "postId": 123, "content": "..." }`
  - 响应：`{ "commentId": "456" }`
- `POST /moment/comment/delete`
  - body：`{ "commentId": 456 }`
- `GET /moment/comment/cursor?postId=123&limit=20&lastId=...`
  - 语义：按 `id` 倒序游标分页

实现位置：`com.miniim.domain.controller.MomentController`

### Call Record（通话记录）
- `GET /call/record/cursor?limit=20&lastId=yyy`
  - 语义：按 `id` 倒序，返回 `id < lastId` 的下一页；`lastId` 为空表示从最新开始
- `GET /call/record/list?pageNo=1&pageSize=20`
  - 语义：普通分页（返回 MyBatis-Plus `Page`）
- 状态（以代码为准）：`ringing/accepted/rejected/canceled/ended/missed/failed`

实现位置：`com.miniim.domain.controller.CallRecordController`

### Group（群）
- `POST /group/create`
  - body：`{ "name": "xxx" }`（`memberUserIds` 已不支持：群成员通过“群码申请入群”加入）
  - 响应：`{ "groupId": "123" }`
- `GET /group/basic?ids=20001,20002`
  - 用途：前端缓存群名（toast/群页标题等）
  - 说明：仅返回“当前用户是成员”的群
  - 响应：`[{ "id":"20001", "name":"xxx", "avatarUrl": null }, ...]`
- `GET /group/profile/by-id?groupId=20001`
  - 用途：群资料（群聊页入口）
  - 返回补充（以代码为准）：`groupCode`、`memberCount`、`myRole`、`isMember`
- `GET /group/profile/by-code?groupCode=ABCDEFGH`
  - 用途：通过群码查群（用于申请入群）
- `GET /group/member/list?groupId=20001`
  - 用途：成员列表（仅成员可见）
  - 说明：返回字段以代码为准；新增 `speakMuteUntil` 用于展示“禁言（发言限制）”状态
- `POST /group/code/reset`
  - 用途：重置群码（owner/admin；服务端限频；返回 42900 表示冷却未到）
- `POST /group/member/kick`
  - 用途：踢人（owner 可踢非 owner；admin 仅可踢 member）
- `POST /group/member/set-admin`
  - 用途：设/取消管理员（仅 owner）
- `POST /group/member/mute`
  - 用途：禁言/解除禁言（发言限制；owner 可禁言 admin/member；admin 仅可禁言 member）
  - body：`{ "groupId": 20001, "userId": 30001, "durationSeconds": 600 }`
    - `0`=解除；`600`=10分钟；`3600`=1小时；`86400`=1天；`-1`=永久
- `POST /group/owner/transfer`
  - 用途：转让群主（仅 owner）
- `POST /group/leave`
  - 用途：退出群（owner 需先转让）

实现位置：`com.miniim.domain.controller.GroupController`
、`com.miniim.domain.controller.GroupProfileController`

### Group Join（申请入群）
- `POST /group/join/request`
  - body：`{ "groupCode":"ABCDEFGH", "message":"..." }`
  - 响应：`{ "requestId":"123" }`（重复申请会返回已有 pending 的 requestId）
- `GET /group/join/requests?groupId=20001&status=pending&limit=20&lastId=...`
  - 用途：群主/管理员查看待处理申请
- `POST /group/join/decide`
  - body：`{ "requestId":123, "action":"accept|reject" }`

实现位置：`com.miniim.domain.controller.GroupJoinController`

### Group Conversations（群会话列表）
- `GET /group/conversation/cursor?limit=20&lastUpdatedAt=...&lastId=...`
  - 语义：按 `updatedAt` 倒序（次序：updatedAt desc, id desc），返回下一页游标
  - 约束：`lastUpdatedAt` 与 `lastId` 必须同时为空或同时存在
  - 返回补充（以代码为准）：
    - `unreadCount`：总未读（基于 `t_group_member.last_read_msg_id` 计算）
    - `mentionUnreadCount`：@我/回复我 未读（基于稀疏索引表 `t_message_mention` 计算）

实现位置：`com.miniim.domain.controller.GroupConversationController`

### Group Messages（群消息查询）
- `GET /group/message/cursor?groupId=xxx&limit=20&lastId=yyy`
  - 语义：按 `id` 倒序，返回 `id < lastId` 的下一页；`lastId` 为空表示从最新开始
- `GET /group/message/since?groupId=xxx&limit=50&sinceId=zzz`
  - 语义：按 `id` 升序，返回 `id > sinceId` 的增量；`sinceId` 为空表示从 0 开始

实现位置：`com.miniim.domain.controller.GroupMessageController`

---

## WebSocket 送达/已读/补发（成员游标，方案B）

- 服务端不再用 `t_message.status` 表达“每个用户的送达/已读”；改为推进成员维度游标：`t_single_chat_member.last_delivered_msg_id/last_read_msg_id`、`t_group_member.last_delivered_msg_id/last_read_msg_id`。
- 接收方确认（客户端 → 服务端）：`type="ACK"` + `serverMsgId`（消息 id）
  - 送达：`ackType="delivered"`（兼容：`ack_receive` / `received`）
  - 已读：`ackType="read"`（兼容：`ack_read`）
- 离线补发：用户 `AUTH` 成功后，服务端按游标拉取 `id > last_delivered_msg_id` 的未投递区间并下发；可选兜底定时补发默认关闭（`im.cron.resend.enabled=true` 才启用）。

## WebSocket

- 服务端入口：`com.miniim.gateway.ws.NettyWsServer`
- 握手鉴权：`com.miniim.gateway.ws.WsHandshakeAuthHandler`
- 帧处理：`com.miniim.gateway.ws.WsFrameHandler`
- 消息封装：`com.miniim.gateway.ws.WsEnvelope`

具体监听地址/路径等以 `src/main/resources/application.yml` 配置为准。

### 在线路由（Redis routeKey）
- 网关实例在连接鉴权成功后，会写入 `im:gw:route:<userId> = <serverId>|<connId>`（带 TTL），用于“多实例路由定位”。
- 同一实例内允许同一 `userId` 同时存在多个 WS 连接（例如多个浏览器标签页）；仅当该 `userId` 的最后一个连接断开时才会删除 routeKey，避免在线状态抖动。

### 握手鉴权（客户端注意事项）
- 非浏览器客户端：可在握手时使用 `Authorization: Bearer <accessToken>`
- 浏览器端：WebSocket API 无法自定义握手 header，需用 query 传递：
  - `ws://127.0.0.1:9001/ws?token=<accessToken>`（或 `accessToken=<...>`）
- 兼容逻辑：连接建立后仍建议发送 `AUTH` 帧（便于统一触发离线补发等逻辑）

---

## WebSocket 业务协议约定（单聊 v1）

### 目标
- 单聊/群聊/好友申请等实时链路统一走 WebSocket；HTTP 仅用于“列表展示/查询类”接口。

### 消息类型
- `AUTH`：首包鉴权（兼容旧客户端）。
- `REAUTH`：续期（刷新服务端记录的 token 过期时间）。
- `SINGLE_CHAT`：单聊发送消息（当前仅 TEXT）。
- `GROUP_CHAT`：群聊发送消息（当前仅 TEXT；重要消息= @我/回复我）。
- `GROUP_NOTIFY`：群聊新消息通知（不含消息体；客户端收到后走 HTTP `/group/message/since` 拉取增量）。
- `FRIEND_REQUEST`：发起好友申请（先落库，必要时 best-effort 推送给对方）。
- `GROUP_JOIN_REQUEST`：有人申请入群（服务端推送给群主/管理员，best-effort）。
- `GROUP_JOIN_DECISION`：入群申请处理结果（服务端推送给申请者，best-effort）。
- `CALL_INVITE`：单聊视频通话邀请（WebRTC offer）。
- `CALL_INVITE_OK`：邀请成功（服务端为本次通话分配 `callId`）。
- `CALL_ACCEPT`：接听（WebRTC answer）。
- `CALL_REJECT`：拒绝来电。
- `CALL_CANCEL`：主叫取消呼叫（未接听前）。
- `CALL_END`：挂断（通话中或异常结束）。
- `CALL_TIMEOUT`：超时未接听。
- `CALL_ICE`：WebRTC ICE candidate 交换。
- `CALL_ERROR`：通话相关错误回包。
- `ACK`：业务回执（用于幂等确认/接收确认）。
- `ERROR`：错误回包。

通用约定：
- 服务端会对 `body`/`message` 做违禁词替换（命中则替换为 `***`），不改变消息语义字段（type/to/groupId 等）。

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

---

## GROUP_CHAT（客户端 ↔ 服务端）

### 请求（客户端 → 服务端）
- 必填字段：
  - `type="GROUP_CHAT"`
  - `clientMsgId`：客户端幂等键（客户端重发必须保持不变）
  - `groupId`：群 id
  - `body`：文本内容（TEXT）
- 可选字段：
  - `mentions`：`@` 提及的 userId 列表（字符串数组，避免 JS number 精度问题；用于服务端计算“重要消息”并落库稀疏索引）
  - `replyToServerMsgId`：回复/引用的目标消息 id（服务端消息 id，通常等于 msgId 的字符串形式）
  - `msgType`：当前建议固定 `TEXT`
  - `ts`：客户端时间戳（仅用于展示/诊断）

### ACK（服务端 → 发起方）
- 落库成功后回：`type="ACK"`
  - `ackType="SAVED"`
  - `clientMsgId` 原样回传
  - `serverMsgId`：服务端生成的消息唯一标识（建议=msgId 的字符串形式）

### 投递（服务端 → 群成员）
- 投递 payload 为 `type="GROUP_CHAT"`，包含：
  - `from`、`groupId`、`clientMsgId`、`serverMsgId`、`body`、`msgType`
  - `important=true`：该条消息对“当前接收方”是否重要（@我/回复我）；非重要时该字段为空


### ACK_RECEIVED（接收方 → 服务端）
- 用途：接收方确认“已收到消息”，服务端据此更新数据库消息状态（`RECEIVED`）。
- 当前实现必填字段（以代码为准）：
  - `type="ACK"`
  - `ackType="ack_receive"`（兼容：`received`）
  - `clientMsgId`（发送方的 clientMsgId，来自服务端下发 `SINGLE_CHAT.clientMsgId`）
  - `to`：原发送方 userId
  - `serverMsgId`：服务端消息 id（建议必填，便于精确更新）

### 幂等与重试（发送方）
- 发送方以“收到 `ACK(SAVED)`”作为服务端落库确认；未收到则客户端按 `clientMsgId` 重发。
- 服务端幂等键：`(fromUserId + '-' + clientMsgId)`；重复请求应返回相同 `serverMsgId`，避免重复落库。

---

### 单聊已读/未读（HTTP）
- `GET /single-chat/member/state?peerUserId=xxx`
  - 返回：`singleChatId`、`myLastReadMsgId`、`peerLastReadMsgId`
  - 用途：前端在单聊页面展示“已读/未读”，并可用于调试

### 会话免打扰（DND）（HTTP）
- `GET /dnd/list`
  - 用途：拉取当前用户已开启免打扰的会话（用于跨端同步）
  - 响应：`{ "dmPeerUserIds": ["10001","10002"], "groupIds": ["20001"] }`
- `POST /dnd/dm/set`
  - body：`{ "peerUserId": "10001", "muted": true }`
  - 说明：仅修改“自己这一侧”的免打扰；不影响消息收发；不通知对方
- `POST /dnd/group/set`
  - body：`{ "groupId": "20001", "muted": true }`
  - 说明：仅修改“自己这一侧”的免打扰；不影响消息收发；不通知对方；需要是群成员

## MESSAGE_REVOKE（客户端 → 服务端）

> 目标：仅发送者可撤回，且发送后 2 分钟内有效。服务端保留原文，但对外输出统一展示“已撤回”。

### 请求（客户端 → 服务端）
- 必填字段：
  - `type="MESSAGE_REVOKE"`
  - `serverMsgId`：要撤回的目标消息 id
- 可选字段：
  - `clientMsgId`：用于客户端关联撤回请求与错误回包
  - `ts`：客户端时间戳（仅用于展示/诊断）

### ACK（服务端 → 发起方）
- 成功后回：`type="ACK"`
  - `ackType="revoked"`
  - `serverMsgId`：目标消息 id

### 推送（服务端 → 相关方）
- `type="MESSAGE_REVOKED"`，用于让在线端即时更新 UI：
  - `serverMsgId`：被撤回的目标消息 id
  - `from`：撤回者 userId
  - 单聊：`to` = 对端 userId
  - 群聊：`groupId` = 群 id
  - `ts`：服务端时间戳（毫秒）

### 常见错误码（`type="ERROR".reason`）
- `missing_server_msg_id`：未传 `serverMsgId`
- `bad_server_msg_id`：`serverMsgId` 非法
- `message_not_found`：目标消息不存在
- `not_message_sender`：非发送者
- `revoke_timeout`：超过 2 分钟窗口
- `internal_error`：服务端内部错误

---

## FRIEND_REQUEST（客户端 → 服务端）

### 请求（客户端 → 服务端）
- 必填字段：
  - `type="FRIEND_REQUEST"`
  - `clientMsgId`：客户端幂等键（客户端重发必须保持不变）
  - `to`：目标 userId
- 可选字段：
  - `body`：验证信息（<=256）
  - `ts`：客户端时间戳（仅用于展示/诊断）

### ACK（服务端 → 发起方）
- 落库成功后回：`type="ACK"`
  - `ackType="SAVED"`
  - `clientMsgId` 原样回传
  - `serverMsgId`：本次好友申请的 `requestId`（用于列表/幂等）

### 推送（服务端 → 被申请方）
- 如果被申请方在线：服务端会 best-effort 推送一次 `type="FRIEND_REQUEST"`（不保证送达、不重试）。

### 离线与补发
- 基本原则：未收到 `ACK_RECEIVED` 视为投递失败；由定时任务兜底重发。
- 状态建议：
  - `SAVED`：已落库待确认
  - `DROPPED`：离线/待补发
  - `RECEIVED`：已收到（业务最终态）

### ⚠️ 当前实现备注（以代码为准）
- 定时补发当前扫描 `status=SAVED` 且 `updatedAt` 超时的消息；投递失败（对端不在线）时会把消息置为 `DROPPED`。
- `ACK_RECEIVED` 的数据库更新逻辑依赖 `clientMsgId/serverMsgId/from/to` 多条件；如客户端字段缺失可能导致 0 行更新（建议长期演进为“以 `serverMsgId` 为主键定位 + to_user_id 校验”）。

---

## WebRTC 单聊视频通话（Phase1，WS 信令）

说明：该功能基于 WebRTC（浏览器）实现，服务端仅负责信令转发与通话记录落库。当前仅保证“同一台电脑、同一浏览器两个窗口”联调场景；无 TURN 时部分网络可能无法互通属于已知限制。

### CALL_INVITE（主叫 → 服务端）
- 必填字段：
  - `type="CALL_INVITE"`
  - `clientMsgId`
  - `to`：被叫 userId
  - `callKind="video"`
  - `sdp`：offer.sdp

### CALL_INVITE_OK（服务端 → 主叫）
- 字段：
  - `type="CALL_INVITE_OK"`
  - `clientMsgId`：原样回传
  - `callId`：服务端生成
  - `to`

### CALL_INVITE（服务端 → 被叫）
- 字段：
  - `type="CALL_INVITE"`
  - `callId`
  - `from`（主叫 userId）
  - `to`（被叫 userId）
  - `callKind="video"`
  - `sdp`：offer.sdp

### CALL_ACCEPT（被叫 → 服务端 → 主叫）
- 必填字段：
  - `type="CALL_ACCEPT"`
  - `callId`
  - `sdp`：answer.sdp

### CALL_REJECT / CALL_CANCEL / CALL_END
- 必填字段：
  - `callId`
- 可选字段：
  - `callReason`：原因（展示/记录用途）

### CALL_TIMEOUT（服务端 → 双方）
- 字段：
  - `type="CALL_TIMEOUT"`
  - `callId`
  - `callReason="timeout"`

### CALL_ICE（双方互发，经服务端转发）
- 必填字段：
  - `type="CALL_ICE"`
  - `callId`
  - `iceCandidate`
- 可选字段：
  - `iceSdpMid`
  - `iceSdpMLineIndex`

### CALL_ERROR（服务端 → 客户端）
- 字段：
  - `type="CALL_ERROR"`
  - `reason`：错误码（例如 `busy/callee_offline/not_friend/...`）
  - `callReason`：可选展示文本（如 `busy/offline/timeout`）
