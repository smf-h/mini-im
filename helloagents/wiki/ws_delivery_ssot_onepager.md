# WS 投递“一页纸”：DB / Redis 哪个为准（SSOT）

> 目标：把「ACK/幂等/离线补发/重连」的最终口径固定下来，避免“看起来都有但口径不一致”。

## 1) 总原则（先记这三条）

1. **最终事实（SSOT）在 DB**：只要和“用户能看到什么/最终是否送达/已读”有关，最终以 DB 持久化结果为准。
2. **Redis 只做加速/削峰/去重**：Redis 可丢、可过期、可重建；它的值不是“最终事实”，只能用于“快速判断/限流/幂等短路”。
3. **“已送达/已读”不看 `t_message.status`**：当前实现的最终口径是**成员游标（cursor）**，即「某个成员已投递/已读到哪条消息」。

### 1.1 字段与命名（WS/HTTP 统一口径）

- **ID 一律按字符串处理（重要）**：所有语义为“ID/游标”的 long 字段（如 `userId/serverMsgId/groupId/singleChatId`）在 JSON 中一律用字符串，避免前端 JS 精度丢失。
- **`serverMsgId`（WS 字段）**：
  - 语义：消息全局唯一 ID（定位/去重/撤回/ACK 的锚点）。
  - 现状：等于 `t_message.id` 的字符串形式（本文默认 `serverMsgId (= id)`）。
- **`msgSeq`（WS/HTTP 字段）**：
  - 语义：会话内单调递增序列（稳定排序/cursor）。
  - DB 映射：`t_message.msg_seq`（并在 `(single_chat_id,msg_seq)` / `(group_id,msg_seq)` 上唯一）。
- **HTTP 的 `lastSeq/sinceSeq`（HTTP 参数）**：
  - 语义：与 `msgSeq` 同口径，都是“会话内序列游标”（单聊/群聊各自独立序列）。
  - 客户端建议：本地持久化游标时保存“每个会话的最大 `msgSeq`”，调用 HTTP 时传给 `lastSeq/sinceSeq`。
- **`clientMsgId`（WS 字段）**：
  - 语义：客户端幂等键；重试必须保持不变。
  - DB 映射：对应 `t_message.client_msg_id`（以及相关业务表的 `client_msg_id`）。
- **`ACK` 的 `ackType`（WS 字段）**：
  - 现状允许值：`saved`（服务端→发送方）、`delivered`（接收方→服务端）、`read`（接收方→服务端）、`revoked`（服务端→撤回发起方）。
  - 兼容：`ack_read` 视为 `read`。
- **错误字段命名**：
  - `AUTH_FAIL.reason` / `ERROR.reason`：机器可读字符串；断连语义见下文“失败策略/登录态口径”。
  - `ts`：毫秒时间戳（服务端为准，用于展示/排障）。

### 1.2 有序性口径（发送者有序 + 最终会话有序）

- **发送者有序（强保证，当前实现）**
  - 前提：当前策略为“单端登录（Single-Session）”，同一 `userId` 同时只保留一个已鉴权 WS 连接；同一连接入站消息通过“per-channel 串行队列”处理。
  - 结论：同一发送方在同一会话连续发送 `A → B`，服务端会保证 `msgSeq(A) < msgSeq(B)`，并按该顺序回 `ACK(saved)`（客户端用 `clientMsgId` 关联回执）。
  - 备注：若未来允许多端并存/多连接并发发送，同一 `userId` 的“发送顺序”会退化为“以服务端落库先后为准”，不再等同于各端本地发送顺序（需要额外的 per-user / per-conversation 串行化才能恢复强发送者有序）。
- **最终会话有序（最终一致）**
  - **会话视图的唯一排序口径是 `msgSeq`**：客户端展示/游标/去重均应以 `msgSeq` 为主（`serverMsgId` 仅用于定位/撤回/幂等锚点）。
  - WS 实时推送允许 best-effort：可能乱序、可能缺口（例如背压降级为 `GROUP_NOTIFY`、连接短暂不可写等）。
  - 一旦出现缺口（gap）或重连：通过“离线补发/HTTP since 拉取”补齐 `msgSeq > cursor` 的区间；补齐后按 `msgSeq` 排序即可收敛为一致的会话视图。
  - `delivered/read` ACK 可重复/乱序；服务端只做 **cursor 单调推进**（只前进不回退），最终状态以成员游标为准。

## 2) 哪些状态以 DB 为准（SSOT）

### 2.1 消息本体（`t_message`）
- **以 DB 为准：**
  - `t_message` 是否存在（是否落库、是否撤回）
  - `t_message.client_msg_id` 与服务端去重后的 `id/server_msg_id` 绑定关系（最终落库结果）
- **备注：**
  - `t_message.status` 里 `DELIVERED/READ/RECEIVED/DROPPED` 属于历史/预留口径，**不作为“已送达/已读”的最终判断依据**（最终看游标）。

### 2.2 单聊成员投递/已读游标（`t_single_chat_member`）
- **以 DB 为准（SSOT）：**
  - 每个成员的 `last_delivered_msg_seq`：该成员“已投递到”的最大 `msgSeq`
  - 每个成员的 `last_read_msg_seq`：该成员“已读到”的最大 `msgSeq`
- **性质：**
  - **单调递增**（只前进不回退），天然幂等（重复 ACK 只会推进到同一个或更大值）。

### 2.3 群聊成员投递/已读游标（`t_group_member`）
- **以 DB 为准（SSOT）：**
  - 每个成员的 `last_delivered_msg_seq / last_read_msg_seq`（同单聊语义）

### 2.4 ACK 明细表（`t_message_ack`）
- **决策（本项目）：弃用写入（仅保留表）**
  - 不写入 `t_message_ack`；送达/已读 SSOT 仍以成员游标（cursor）为准。
  - 主要原因：群聊场景下数据量与写放大会被放大（每条消息 × 成员 × 设备 × ACK 类型），且容易引入“双口径”（cursor vs 明细表）造成解释困难。
- **你关心的：审计排障 / 维度统计**
  - 如果写入明细表，价值在于“逐消息逐设备”的时间线：能回答某条消息对某个用户/设备是否 `DELIVERED/READ`、发生时间、是否重复/乱序等。
  - 维度统计可做：投递率/已读率、`SAVED→DELIVERED` 延迟分布、`DELIVERED→READ` 延迟分布；维度通常来自 `t_message`（chat_type/msg_type/group_id 等）+ `t_message_ack.device_id`（端维度）。
- **弃用后的推荐替代（更轻）**
  - **审计排障**：在 `ACK/补发/推送失败` 关键点打结构化日志（字段包含 `serverMsgId/chatType/chatId/fromUserId/ackUserId/ackType/deviceId/connId/instanceId/prevCursor/newCursor/result`），配合 DB cursor 能还原 80% 的现场。
  - **维度统计**：用指标（counter/histogram）统计 ACK 数量与延迟分布；需要细到“某条消息某个设备”的再考虑启用明细表（但仍不作为 SSOT）。

## 3) 哪些状态以 Redis 为准（但不是最终事实）

### 3.1 发送幂等短路（Redis + 本地缓存）
- **用途：** 防止“客户端重试/网络抖动”导致重复落库与重复推送。
- **口径：**
  - **Redis 的 `SETNX/putIfAbsent` 结果只用于快速拒绝重复请求**；
  - **最终是否成功发送/落库，仍以 DB 是否写入为准**。
- **失败策略（推荐）：**
  - Redis 可用：严格幂等（fail-fast）。
  - Redis 不可用：降级为本地缓存 best-effort（可能放大重复，但不阻塞主链路）。

### 3.2 WS 连接态/会话态加速（如有）
- Redis/内存里的在线标记、连接映射、临时 token/TTL 等都属于**缓存态**，掉了可重建。

## 4) ACK/补发/重连：状态机口径（大白话版）

### 4.1 发送侧（服务端处理 SEND）
1. **幂等判重（Redis/本地）**：同一个 `clientMsgId`（DB 列：`client_msg_id`）只“认一次”。
2. **落库（DB）**：写 `t_message`，拿到 `serverMsgId (= id)`。
3. **回 `ACK(ackType="saved")` 给发送者（WS）**：告诉发送者“我已经存下来了”，此时客户端可以把该消息标记为“已发送成功（至少落库）”。

### 4.2 接收侧（客户端收到消息后回 ACK）
- 客户端发 `ACK(ackType="delivered"/"read")`（含 `serverMsgId`）。
- 服务端处理 ACK：
  - **推进成员游标（DB SSOT）**：把对应成员的 `last_delivered_msg_seq/last_read_msg_seq` 推进到更大值；
  - 然后**向发送者回推 ACK**（让发送者 UI 能更新对方的送达/已读）。

### 4.3 离线补发（RESEND）
- AUTH/重连后，服务端读取 **DB cursor**（`last_delivered_msg_seq`）：
  - 查出 >cursor 的消息并补发；
  - 客户端收到后再按正常流程回 `ACK(ackType="delivered")` 推进 cursor。
- **结论：**补发的“应发列表”以 DB 为准；Redis 只做加速，不可作为“哪些要补发”的最终依据。

### 4.4 Cursor 模型前提（必须遵守）

- **只 ACK 已收到的消息**：客户端只能对“自己确实已经收到/已展示”的 `serverMsgId` 发送 `ackType=delivered/read`（兼容：`ack_read`），禁止“猜测推进”。
  - 否则：服务端补发按 `last_delivered_msg_seq` 起算，游标被推进后，被跳过的消息不会再被补发（逻辑丢失）。
- **客户端必须按 `serverMsgId` 去重**：离线补发/重连/兜底补发都可能导致重复下发；cursor 模型默认语义是 at-least-once。
- **ACK 可重复/乱序**：服务端以游标单调递增处理（只前进不回退），重复 ACK 不会产生副作用。

## 5) 失败策略（建议写死）

- **WS 推送失败/背压：**优先“断开连接 + 让客户端重连触发补发”，不要在服务端无限堆积（避免 OOM）。
- **ACK 重复/乱序：**允许，按 cursor 单调递增处理即可（天然幂等）。
- **DB 写失败：**直接失败返回；不要给 `SAVED`。
- **Redis 写失败：**降级（本地 best-effort），但必须保证 DB 仍是最终事实。

### 5.1 WS 背压（slow consumer）与断连策略（以代码为准）

本项目把“慢消费者”视为连接层风险：宁可断开让客户端重连补发，也不在服务端无限堆积。

- **触发条件：channel 变为不可写（`!ch.isWritable()`）**
  - Netty write buffer 超过高水位线后会变为 unwritable。
  - 相关配置（默认值见代码）：
    - `im.gateway.ws.backpressure.write-buffer-low-water-mark-bytes`：默认 256KB
    - `im.gateway.ws.backpressure.write-buffer-high-water-mark-bytes`：默认 512KB
- **处理策略：**
  1) **快速丢弃（fail-fast）**：当 `dropWhenUnwritable=true`（默认）时，业务写出会直接失败（避免继续堆积）。
  2) **延迟断连**：连接持续 unwritable 超过 `im.gateway.ws.backpressure.close-unwritable-after-ms`（默认 3000ms）后，服务端会主动关闭该连接。
  3) **关键消息兜底：**对 `ERROR` / `CALL_*` 这类关键 push，若目标连接已 unwritable，服务端会尽量关闭该连接（避免“客户端永远收不到被踢/会话失效”的控制信号）。
- **客户端约定：**
  - 断连后按指数退避重连；
  - 重连鉴权成功后由服务端触发离线补发（按 cursor 区间补齐）。

### 5.2 群聊投递策略与降级（推消息体 vs 通知后拉取 vs 不推）

群聊实时下发存在 fanout 写放大，因此本项目对群聊引入“策略切换”，并在极端场景下允许降级为“不推”。

**策略模式（配置：`im.group-chat.strategy.mode`）：**

- `push`：策略1（推消息体）：在线端直接收到 `type="GROUP_CHAT"`（含 `body`）。
- `notify`：策略2（通知后拉取）：在线端收到 `type="GROUP_NOTIFY"`（不含 `body`），客户端再走 HTTP `GET /group/message/since` 拉取增量。
- `none`：不推：不推 `GROUP_CHAT/GROUP_NOTIFY`，靠“打开会话/列表刷新/离线补发”兜底获取消息。
- `auto`（默认）：按阈值在 `push/notify` 间自动选择，并在极端场景下跳过 notify。

**`auto` 的切换/降级阈值（默认值见 `application.yml`）：**

- `group-size-threshold`：群成员数 ≥ 2000 → 优先 `notify`
- `online-user-threshold`：在线人数 ≥ 500 → 优先 `notify`
- `notify-max-online-user`：在线人数 ≥ 2000 → **不推 notify**（降级为 none）
- `huge-group-no-notify-size`：群成员数 ≥ 10000 → **不推 notify**（降级为 none）

**为什么 `notify/none` 仍是“正确的”**

- SSOT 在 DB：只要落库成功，最终可通过 HTTP 拉取/重连补发拿到数据；
- 实时推送只是加速，不是最终事实；
- 在极端 fanout 场景下，“通知后拉取/不推”是必要取舍，用于保护网关与 Redis/DB。

### 5.3 补发上限与兜底（当前实现）

- 离线补发触发点：连接 `AUTH` 成功后可触发一次补发（同一连接只触发一次）。
- 补发范围：按成员游标 `last_delivered_msg_seq` 查询 `msg_seq > cursor` 的未投递区间，并按升序下发（单次有上限）。
- 单次补发上限：服务端每次补发最多拉取 `LIMIT=200` 条单聊 + `LIMIT=200` 条群聊（总量受限，避免重连风暴时压垮 DB）。
- 跨实例短锁：同一用户在短窗口内重复触发补发会被 Redis 短 TTL 锁抑制（避免重连风暴放大 DB 压力）。

### 5.4 错误码与断连语义（精简口径）

为了避免“前后端理解不一致”，本项目把错误回包分为两类：

- **鉴权/门禁类（强断连）**：服务端会主动断开连接，客户端应走统一重连/回登录逻辑。
  - `AUTH_FAIL:*`：鉴权失败，一律断连（不按 reason 分流）。
  - `ERROR:kicked/session_invalid/token_expired/unauthorized/reauth_uid_mismatch/too_many_requests`：视为会话不可用或门禁触发，一律断连。
  - `ERROR:auth_timeout`：3s 内未完成 AUTH，一律断连。
- **业务校验/系统繁忙类（不强制断连）**：用于标记某次请求失败，连接通常保持；客户端按 `clientMsgId` 展示失败并决定是否重试。
  - 典型：`ERROR:server_busy/internal_error/missing_*/body_too_long/not_implemented/...`

## 6) 登录态/多端口径（当前为单端登录踢线）

- **当前实现不是“多端并存”**：同一 `userId` 默认只保留最新的 WS 连接（新连接鉴权成功后会关闭旧连接，包括同一设备的多标签页）。
- **踢线信号：**旧连接会收到 `type="ERROR", reason="kicked"`，随后断开。
- **会话失效信号：**
  - token 的 `sessionVersion(sv)` 不再有效时：WS `AUTH` 会返回 `type="AUTH_FAIL", reason="session_invalid"` 并断开；
  - 已连接在线期间若 `sv` 失效：心跳/续期等链路可能返回 `type="ERROR", reason="session_invalid"` 并断开。
- **未鉴权/鉴权丢失门禁：**未鉴权连接发送业务消息会收到 `type="ERROR", reason="unauthorized"` 并断开（防刷包/防灰色状态）。
- **token 到期：**在线期间 token 到期会收到 `type="ERROR", reason="token_expired"` 并断开（客户端应 refresh 或回登录）。
- **与投递/补发的关系：**被踢下线的一端不再接收推送；用户后续在“仍在线的最新连接”上完成 `delivered/read` ACK 推进 cursor；离线补发仍按 cursor 区间进行。
- **网页端如何验证：**用“普通窗口 + 无痕窗口/另一个浏览器”同账号登录，先登录的一端应收到 `kicked` 并断开。

## 7) 本次已对齐的“历史不一致”项（你只需要记结论）

- `AckType`：最终定义为 `1=SAVED, 2=DELIVERED, 3=READ`（代码/表注释/知识库统一）。
- `t_message_ack.ack_type` 注释：历史上写成 `1=SAVED,2=READ`，本次通过新增迁移修正为上面口径（不改旧 V1）。
- `MessageStatus`：补齐 `5=RECEIVED,6=DROPPED` 的注释；并明确 `DELIVERED/READ` 最终看 cursor，不看 `t_message.status`。
