# API 手册

## 数字精度（重要）

- 后端 ID 多为 Java `long/Long`（例如 `userId/id/singleChatId/serverMsgId/requestId`）。浏览器端若按 JS `Number` 解析，超过 `2^53-1` 会发生精度丢失（表现为“四舍五入/末尾变 0”）。
- **协议约定：所有语义为“ID”的 long/Long 字段，JSON 一律输出为字符串**（例如 `"toUserId":"123"`），避免前端误用 Number。
- 非 ID 的 long/Long（例如分页 `total/size/current`）仍保持 JSON 数字类型。
- 客户端建议：凡是语义为“ID/游标”的字段，一律按字符串处理（展示/比较/拼接），不要转 `Number(...)`。

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
  - `unreadCount`：当前用户未读条数（基于 `t_single_chat_member.last_read_msg_seq` 计算）
  - `peerLastReadMsgSeq`：对方已读游标（用于“已读/未读”展示）

实现位置：`com.miniim.domain.controller.SingleChatConversationController`

### Single Chat Messages（单聊消息查询）
- `GET /single-chat/message/cursor?peerUserId=xxx&limit=20&lastSeq=yyy`
  - 语义：按 `msgSeq` 倒序，返回 `msgSeq < lastSeq` 的下一页；`lastSeq` 为空表示从最新开始
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
    - `unreadCount`：总未读（基于 `t_group_member.last_read_msg_seq` 计算）
    - `mentionUnreadCount`：@我/回复我 未读（基于稀疏索引表 `t_message_mention` 计算）

实现位置：`com.miniim.domain.controller.GroupConversationController`

### Group Messages（群消息查询）
- `GET /group/message/cursor?groupId=xxx&limit=20&lastSeq=yyy`
  - 语义：按 `msgSeq` 倒序，返回 `msgSeq < lastSeq` 的下一页；`lastSeq` 为空表示从最新开始
- `GET /group/message/since?groupId=xxx&limit=50&sinceSeq=zzz`
  - 语义：按 `msgSeq` 升序，返回 `msgSeq > sinceSeq` 的增量；`sinceSeq` 为空表示从 0 开始

实现位置：`com.miniim.domain.controller.GroupMessageController`

---

## WebSocket 送达/已读/补发（成员游标，方案B）

- 服务端不再用 `t_message.status` 表达“每个用户的送达/已读”；改为推进成员维度游标：`t_single_chat_member.last_delivered_msg_seq/last_read_msg_seq`、`t_group_member.last_delivered_msg_seq/last_read_msg_seq`。
- 接收方确认（客户端 → 服务端）：`type="ACK"` + `serverMsgId`（消息 id）
  - 送达：`ackType="delivered"`
  - 已读：`ackType="read"`（兼容：`ack_read`）
- 离线补发：用户 `AUTH` 成功后，服务端按游标拉取 `msg_seq > last_delivered_msg_seq` 的未投递区间并下发；可选兜底定时补发默认关闭（`im.cron.resend.enabled=true` 才启用）。

## WebSocket

- 服务端入口：`com.miniim.gateway.ws.NettyWsServer`
- 握手处理（HTTP Upgrade）：`io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler`（AUTH-first：握手阶段不鉴权）
- 帧处理：`com.miniim.gateway.ws.WsFrameHandler`
- 消息封装：`com.miniim.gateway.ws.WsEnvelope`

具体监听地址/路径等以 `src/main/resources/application.yml` 配置为准。

### 在线路由（Redis routeKey）
- 网关实例在连接鉴权成功后，会写入 `im:gw:route:<userId> = <serverId>|<connId>`（带 TTL），用于“多实例路由定位”。
- `SessionRegistry` 在实现上支持同一 `userId` 在本机存在多个 channel（用于 close/写回等管理），但当前业务策略是“单端登录”：新连接鉴权成功后会关闭旧连接（包括同一设备多标签页）。
- routeKey 删除策略：仅当本机该 `userId` 的连接集合为空时，才尝试 `deleteIfMatch(userId, connId)` 删除 routeKey（避免在线状态抖动/误删）。

### 单端登录（Single-Session）与踢线（KICK）

> 结论：当前实现是“单端登录”，不是“多端并存”。

- 同一 `userId` 新连接鉴权成功后，服务端会关闭该 `userId` 的其他 WS 连接（包括同一设备的多标签页/多窗口），仅保留最新连接。
- 旧连接会收到：`type="ERROR", reason="kicked"`，随后断开连接。
- 目的：避免“同账号多连接重复收消息/重复 ACK”导致投递语义与 UI 行为变复杂；MVP 阶段以单端为准。

### 鉴权（AUTH-first，已实现）

- WS URL：`ws://127.0.0.1:9001/ws`（URL 不携带 `token/accessToken` query）。
- 连接建立后，客户端必须在 `3s` 内发送：`{ "type":"AUTH", "token":"<accessToken>" }`
  - 超时未鉴权：服务端先回 `type="ERROR", reason="auth_timeout"`，随后断连
- 未鉴权状态白名单：允许 `AUTH`、`PING`、`PONG`；其余（包含 `REAUTH`）一律 `ERROR unauthorized` 并断连
- `AUTH_FAIL`：服务端发出 `AUTH_FAIL` 后立即断连（不按 reason 分流）
- `REAUTH`：仅允许在已鉴权连接上使用（刷新 token 过期时间，不触发离线补发）

---

## WebSocket 业务协议约定（单聊 v1）

### 目标
- 单聊/群聊/好友申请等实时链路统一走 WebSocket；HTTP 仅用于“列表展示/查询类”接口。

### ACK 语义速查（按 `ackType`）

> 约定：`ackType` 大小写不敏感（服务端/前端均做了兼容）；下表以“推荐小写”展示。

| ackType | 方向 | 触发时机 | 必填字段（以代码为准） | 对 SSOT 的影响 | 备注 |
|---|---|---|---|---|---|
| `saved` | 服务端 → 发送方 | 消息/好友申请等**落库成功**后回包 | `type="ACK"`, `clientMsgId`, `serverMsgId` | **不推进 cursor**；仅表示“已持久化” | 单聊/群聊：用于发送确认与幂等收敛；ACK 里可能携带 `body`（服务端回传净化后的内容） |
| `delivered` | 接收方 → 服务端 | 接收方收到消息帧后确认“已收到” | `type="ACK"`, `ackType`, `serverMsgId`, `to` | 推进 `last_delivered_msg_seq`（cursor SSOT） | 单聊：服务端 best-effort 回推 `ACK(delivered)`（含 `msgSeq`）给发送方用于 UI 展示 |
| `read` | 接收方 → 服务端 | 接收方在聊天页可见/停留后确认“已读到某条” | `type="ACK"`, `ackType`, `serverMsgId`, `to` | 推进 `last_read_msg_seq`（并隐式推进 delivered） | 兼容：`ack_read`；单聊服务端 best-effort 回推 `ACK(read)`（含 `msgSeq`）给发送方用于 UI 展示 |
| `revoked` | 服务端 → 撤回发起方 | 撤回成功后确认回包 | `type="ACK"`, `ackType="revoked"`, `serverMsgId` | 不推进 cursor | 同时会广播 `type="MESSAGE_REVOKED"` 给相关方，让在线 UI 即时更新 |

### 有序性口径（发送者有序 + 最终会话有序）

- **唯一排序口径：`msgSeq`**。`serverMsgId` 只作为“全局唯一 ID/锚点”（定位/撤回/幂等），不作为会话排序依据。
- **发送者有序（当前实现的强保证）**：在“单端登录（Single-Session）”前提下，同一 `userId` 同时只有一个已鉴权连接；同一连接的入站消息在服务端按串行队列处理，因此同一发送方连续发送 `A → B` 会对应 `msgSeq(A) < msgSeq(B)`，并按序回 `ACK(saved)`。
- **最终会话有序（最终一致）**：WS 实时推送允许 best-effort（可乱序/可缺口），客户端必须按 `msgSeq` 排序展示；发现 gap 或收到 `GROUP_NOTIFY` 时，用 `sinceSeq=<本地最大msgSeq>` 走 HTTP 增量拉取补齐，补齐后会话视图按 `msgSeq` 收敛为一致。
- **ACK 乱序不影响最终状态**：`delivered/read` ACK 可重复/乱序；服务端按 cursor 单调推进（只前进不回退），最终以成员游标（`last_*_msg_seq`）为准。

### 消息类型
- `AUTH`：首包鉴权（AUTH-first 唯一入口）。
- `AUTH_OK`：鉴权成功（服务端回包）。
- `AUTH_FAIL`：鉴权失败（服务端回包，随后断开连接）。
- `REAUTH`：续期（刷新服务端记录的 token 过期时间）。
- `SINGLE_CHAT`：单聊发送消息（当前仅 TEXT）。
- `GROUP_CHAT`：群聊发送消息（当前仅 TEXT；重要消息= @我/回复我）。
- `GROUP_NOTIFY`：群聊新消息通知（不含消息体；客户端收到后走 HTTP `/group/message/since?sinceSeq=<本地最大msgSeq>` 拉取增量）。
  - 说明：该通知是否下发取决于群聊投递策略（见下方 `GROUP_CHAT` 章节“投递策略与降级”）；极端 fanout 场景下可能会降级为“不推通知”，此时客户端需要在“打开群聊页/刷新会话列表”时通过 HTTP 对账。
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

### WS 鉴权/会话失效（单端登录口径）

- `AUTH`（客户端 → 服务端）：首包鉴权（AUTH-first 唯一入口）。
  - 必填：`type="AUTH"`、`token`
  - 成功：`type="AUTH_OK"`（服务端绑定身份，并触发离线补发等逻辑）
  - 失败：`type="AUTH_FAIL"`, `reason=missing_token|invalid_token|session_invalid`，随后断开连接
- `REAUTH`（客户端 → 服务端）：续期（刷新服务端记录的 token 过期时间，不触发离线补发）。
  - 常见失败：`ERROR reason=reauth_uid_mismatch|session_invalid|unauthorized`（失败时会断开连接）
- 已在线期间 token 失效：
  - 心跳/空闲保活可能回 `ERROR reason=token_expired|session_invalid` 并断开连接
- 单端踢线：
  - 同账号在别处建立新连接并鉴权成功后，旧连接会收到 `ERROR reason=kicked` 并断开

### WS 错误码总表（`AUTH_FAIL` vs `ERROR`）

#### 边界（什么时候用 `AUTH_FAIL`，什么时候用 `ERROR`）

- `AUTH_FAIL`：只用于 `type="AUTH"` 鉴权失败（服务端会立即断开连接）。
- `ERROR`：除 `AUTH_FAIL` 以外的所有错误回包（业务校验/限流/会话失效/踢线/协议违规等）。是否断开连接取决于错误类型（见下表）。
- AUTH-first：握手阶段不鉴权，不存在“token 导致的握手 401”；token 相关错误统一通过 WS `AUTH_FAIL` 返回，且 `3s` 内未完成 `AUTH` 会触发 `ERROR auth_timeout` 并断连。

#### `AUTH_FAIL`（WS 帧鉴权失败）

| 场景 | 返回 | reason | 是否断连 | 客户端建议 |
|---|---|---|---|---|
| 未传 token | `type="AUTH_FAIL"` | `missing_token` | 是 | 修复接入：确保 `AUTH.token` 有值 |
| token 无效/不可解析 | `type="AUTH_FAIL"` | `invalid_token` | 是 | 清理本地 token，重新登录 |
| token 已失效（sv 不一致） | `type="AUTH_FAIL"` | `session_invalid` | 是 | 清理本地 token，提示“会话已失效”，跳转登录 |

#### `ERROR`（通用错误回包）

> 说明：服务端会尽量回传 `clientMsgId/serverMsgId` 方便前端定位请求；是否断连以当前实现为准（代码里多处会主动 `close()`）。

| reason | 常见触发 | 是否断连 | 客户端统一处理策略（建议） |
|---|---|---|---|
| `kicked` | 单端登录：同账号新连接鉴权成功后踢旧连接 | 是 | 清 token → toast “账号在另一处登录” → 跳转登录页 |
| `session_invalid` | 在线期间检测到 sv 失效（心跳/REAUTH/网关门禁复验等） | 是 | 清 token → 跳转登录页（避免无限重连） |
| `token_expired` | 在线期间 accessToken 到期（心跳/门禁） | 是 | 先走 refresh（如有）获取新 token，否则清 token → 跳转登录 |
| `unauthorized` | 未鉴权/鉴权丢失的连接发送业务消息（或 `REAUTH` 在未绑定 uid 时） | 是（通常） | 直接断开并引导重新鉴权/重连；若无 token 则跳登录 |
| `auth_timeout` | 连接建立后 `3s` 内未完成 `AUTH` | 是 | 视为接入错误或弱网异常：重连后立即发 `AUTH`；若无 token 则跳登录 |
| `reauth_uid_mismatch` | `REAUTH` 的 token.uid 与当前连接绑定 uid 不一致 | 是 | 视为会话异常：清 token → 跳登录 |
| `too_many_requests` | 网关限流（连接级/用户级）触发 | 是 | 断开后指数退避重连；并在前端做发送节流/合并 ACK |
| `bad_json` | 收到无法解析的 JSON | 可能（短窗累计触发） | 视为客户端 bug：停止发送，打印原始包与版本信息，必要时重连 |
| `missing_type` | WS 包缺 `type` | 可能（短窗累计触发） | 同上（协议/客户端 bug） |
| `not_implemented` | 未支持的 `type` | 可能（短窗累计触发） | 同上（版本不一致/协议不匹配） |
| `server_busy` | 网关队列/线程池拥塞（例如 inbound queue 拒绝） | 否 | 标记该 `clientMsgId` 失败；稍后重试（保持 `clientMsgId` 不变以便幂等） |
| `internal_error` | 服务端内部异常/超时 | 否（多数业务路径） | 标记失败并允许重试；必要时提示“服务端繁忙，请稍后重试” |
| `missing_msg_id` / `missing_to` / `missing_group_id` / `missing_body` | 请求缺字段 | 否 | 直接提示用户/修复请求构造，不要自动重试 |
| `body_too_long` | 文本过长（单聊/群聊/好友申请各自限制） | 否 | 提示用户缩短内容或拆分发送 |
| `cannot_send_to_self` | 给自己发单聊/好友申请 | 否 | 提示用户修正目标 |
| `missing_ack_type` / `unknown_ack_type` | ACK 缺字段/ackType 不认识 | 否 | 修复客户端实现（ackType 仅支持 delivered/read 等） |
| `missing_server_msg_id` / `bad_server_msg_id` | serverMsgId 缺失/非法（ACK/撤回等） | 否 | 修复客户端实现；一般不应自动重试 |
| `message_not_found` | ACK/撤回指向的消息不存在 | 否 | 视为状态不同步：触发拉取对账或忽略该操作 |
| `ack_not_allowed` | ACK 权限不匹配（例如对不属于自己的消息 ACK） | 否 | 视为客户端 bug 或越权：停止该行为并上报 |

#### 前端统一处理（推荐实现口径）

- **需要立刻退出登录态的错误（统一一条路径处理）：**
  - `AUTH_FAIL: invalid_token/session_invalid`
  - `ERROR: session_invalid/token_expired/unauthorized/kicked/reauth_uid_mismatch`
  - 行为：关闭 WS → 清理 token → 跳转登录页（并 toast 提示原因）
- **可重试类：**`ERROR server_busy/internal_error/too_many_requests`
  - 行为：对发送类请求保持 `clientMsgId` 不变做幂等重试；对连接级限流走指数退避重连
- **不可重试（用户输入/协议问题）：**缺字段/超长/未实现/ACK 参数错误等
  - 行为：提示用户或打点上报，避免自动重试刷爆

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


### 投递策略与降级（推消息体 vs 通知后拉取 vs 不推）

> 目标：群聊 fanout 场景下控制写放大；实时推送只是加速，最终以 DB 拉取/补发为准。

当前实现支持 3 种策略（配置：`im.group-chat.strategy.mode`）：

- `push`：推消息体（在线端会直接收到 `GROUP_CHAT`）
- `notify`：通知后拉取（在线端收到 `GROUP_NOTIFY`，客户端再调用 `GET /group/message/since` 拉取增量）
- `none`：不推（不推 `GROUP_CHAT/GROUP_NOTIFY`，客户端靠“打开群聊页/刷新列表/离线补发”兜底）
- `auto`（默认）：按阈值自动选择 `push/notify`，并在极端场景下跳过 notify

自动策略阈值（默认值见 `application.yml`）：

- `group-size-threshold=2000`：群成员数达到阈值时优先 `notify`
- `online-user-threshold=500`：在线人数达到阈值时优先 `notify`
- `notify-max-online-user=2000`：在线人数过多时**不推 notify**（降级为 `none`）
- `huge-group-no-notify-size=10000`：超大群直接**不推 notify**（降级为 `none`）

客户端接入约定：

- 收到 `GROUP_CHAT`：正常展示 + 去重（按 `serverMsgId`）+ 回 `ACK(delivered/read)`
- 收到 `GROUP_NOTIFY`：以 `msgSeq` 作为“最新消息提示”，调用 `GET /group/message/since?sinceSeq=<本地最大msgSeq>` 拉取增量（可忽略提示值，仅作为“是否需要拉取”的 hint）
- 若长时间收不到 `GROUP_CHAT/GROUP_NOTIFY`：不代表无新消息；应在“打开群聊页/刷新会话列表”时走 HTTP 拉取对账

### DELIVERED/READ（接收方 → 服务端，推进 cursor）

- 用途：推进成员游标（cursor），用于“离线补发/已读展示/最终状态”。
- 必填字段（以代码为准）：
  - `type="ACK"`
  - `ackType="delivered"` 或 `ackType="read"`
    - 兼容：`ack_read` 视为 `read`
  - `serverMsgId`：被确认的消息 id（服务端消息 id）
  - `to`：原发送方 userId（当前实现要求必填；实际校验以 `serverMsgId` 解析出的 message 为准）
- 说明：
  - ACK 可重复/乱序；服务端按 cursor 单调递增处理（只前进不回退）。

### 幂等与重试（发送方）
- 发送方以“收到 `ACK(SAVED)`”作为服务端落库确认；未收到则客户端按 `clientMsgId` 重发。
- 服务端幂等键：`(fromUserId + '-' + clientMsgId)`；重复请求应返回相同 `serverMsgId`，避免重复落库。

---

### 单聊已读/未读（HTTP）
- `GET /single-chat/member/state?peerUserId=xxx`
  - 返回：`singleChatId`、`myLastReadMsgSeq`、`peerLastReadMsgSeq`
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

### 离线与补拉（HTTP）
- `FRIEND_REQUEST` 的 WS 推送是 best-effort：对方离线时不保证实时送达。
- 对方登录/刷新后应通过 HTTP 列表接口拉取申请记录（`GET /friend/request/cursor` 或 `GET /friend/request/list`），并在列表页上 `POST /friend/request/decide` 处理。

### ⚠️ 当前实现备注（以代码为准）
- `FRIEND_REQUEST` 的实时推送为 best-effort，不做 WS 重试/补发；最终以 DB 列表查询结果为准。

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
