# 引入 msgSeq（会话内单调序列）并迁移 cursor 口径

## 背景 / 现状

当前消息相关的关键事实：

- `t_message.id`（也作为 `serverMsgId`）是全局唯一 msgId。
- “离线补发 / 未读 / 已读”使用成员表游标：
  - 单聊：`t_single_chat_member.last_delivered_msg_id / last_read_msg_id`
  - 群聊：`t_group_member.last_delivered_msg_id / last_read_msg_id`
- 以上游标与分页、补发、未读统计都用 `t_message.id` 做区间边界（`id > cursor`）。

该实现对 MVP 足够，但与主流 IM 的“会话内稳定序列”有偏差：

- `id` 是全局序列：会话内并不连续，未来分库分表/多写入点时会话内排序与范围查询成本更高、含义不直观。
- 以 `id` 作为会话 cursor，会把“会话内进度”绑定到“全局生成策略”（Snowflake/分片等）。

## 目标

引入 `msgSeq`（会话内单调递增）作为会话内排序与 cursor 的唯一口径：

- `serverMsgId/id`：仍作为**全局唯一定位**（ACK/撤回/回复/引用/排障）
- `msgSeq`：作为**会话内顺序与进度**（分页 cursor、补发区间、未读统计、ACK 推进游标）

## 非目标

- 不引入“逐消息逐成员 ACK 明细”作为 SSOT（仍以成员 cursor 为准）。
- 不改变单端登录/踢线策略。
- 不在本次方案中引入多端并存维度（deviceId 聚合等）。

## 方案对比

### 方案 1：完整切换（推荐）

**核心：** DB 新增 `msg_seq` + 会话 `next_msg_seq`，并将游标与所有范围查询从 `id` 切到 `msg_seq`。

优点：
- 口径与主流一致：会话内稳定排序、cursor 语义清晰。
- 索引与分库分表友好：范围查询天然用 `(chat_id, msg_seq)`。
- 未来扩展：可把 `msgSeq` 作为“会话内消息序列”的 SSOT（对账/去重/同步都更自然）。

缺点：
- 需要一次 DB 迁移 + 历史数据回填（对大表有耗时风险）。
- 需要同步修改 HTTP/WS 的分页与补发相关代码。

### 方案 2：只落库 msgSeq，不迁移 cursor（不推荐）

**核心：** 仅新增 `msg_seq` 字段并写入，但分页/补发/未读仍用 `id`。

优点：
- 改动小、风险低。

缺点：
- 价值有限：核心链路仍绑死 `id`，无法兑现“会话序列”带来的收益。
- 后续迁移仍要再做一次全量改造。

## 推荐方案（采用方案 1）

### 数据库设计

1) `t_message` 新增列与索引

- `msg_seq BIGINT NOT NULL`：会话内序列号
- 索引与约束（示例口径）：
  - `KEY idx_msg_single_id_seq (single_chat_id, msg_seq)`
  - `KEY idx_msg_group_id_seq (group_id, msg_seq)`
  - `UNIQUE KEY uk_single_chat_seq (single_chat_id, msg_seq)`
  - `UNIQUE KEY uk_group_seq (group_id, msg_seq)`

2) `t_single_chat / t_group` 新增序列游标

- `next_msg_seq BIGINT NOT NULL DEFAULT 0`
- 用于分配下一条消息的 `msgSeq`（会话级别自增）。

3) 成员游标列迁移（从 msgId → msgSeq）

为了避免直接改列语义导致历史数据含义混乱，新增新列并切换业务读取：

- 单聊：`t_single_chat_member.last_delivered_msg_seq / last_read_msg_seq`
- 群聊：`t_group_member.last_delivered_msg_seq / last_read_msg_seq`

旧列 `*_msg_id` 暂时保留但不再参与核心链路（后续再清理）。

### msgSeq 分配方式（并发安全）

MySQL 方案：使用 `LAST_INSERT_ID` 技巧原子递增并取回值（同一连接内有效）：

- 单聊：
  - `UPDATE t_single_chat SET next_msg_seq = LAST_INSERT_ID(next_msg_seq + 1) WHERE id = ?;`
  - `SELECT LAST_INSERT_ID();` 得到本次 `msgSeq`
- 群聊同理对 `t_group` 做一次 `UPDATE + SELECT`。

实现要点：

- 分配 `msgSeq` 与写入 `t_message` 在同一个 DB 任务（线程）内完成；是否同一事务不是硬要求（失败会“浪费序列号”，可接受）。
- 用 mapper/service 封装“allocateNextSeq(chatId)”避免在 handler 内散落 SQL。

### 业务链路改造点（以现有代码为准）

1) 写入链路（WS 发送）

- `WsSingleChatHandler`：在 `messageService.save(messageEntity)` 前，先拿到 `singleChatId`，再分配 `msgSeq`，写入 `messageEntity.msgSeq`。
- `WsGroupChatHandler`：在保存 `messageEntity` 前分配 `msgSeq`。

2) ACK 推进游标

- 继续以 `serverMsgId` 定位消息（不改协议）。
- 推进游标时将 `last_*_msg_seq = greatest(old, message.msgSeq)`。

3) 离线补发

- 由 `m.id > last_delivered_msg_id` 迁移为 `m.msg_seq > last_delivered_msg_seq`（并仍按 `msg_seq asc` 下发）。

4) 未读数统计

- 单聊：`count(*) where m.msg_seq > last_read_msg_seq and m.to_user_id = me`
- 群聊：`count(*) where m.msg_seq > last_read_msg_seq and m.from_user_id != me`
- mention 未读：需要 `t_message_mention` join `t_message` 才能用 `msg_seq` 比较，或在 mention 表冗余写入 `msg_seq`（本方案优先选择 join，避免额外写放大）。

5) HTTP 消息分页接口

将现有“按 id 游标”的接口迁移为“按 msgSeq 游标”：

- `GET /single-chat/message/cursor`：`lastId` → `lastSeq`（或保留参数名但语义改为 seq，二选一）
- `GET /group/message/cursor`：同上
- `GET /group/message/since`：`sinceId` → `sinceSeq`

（本仓库前端只有网页端，建议直接统一改参数名，避免语义混淆。）

## Cursor / 乱序 / ACK 的必要取舍（必须写死的前提）

引入 `msgSeq` 能让“会话内顺序与进度”的口径更稳定，但它**不会消灭网络与补发造成的到达乱序**。本项目仍是 at-least-once 投递模型（可能重复下发），因此必须明确以下前提，否则会出现“跳 ACK 导致补发缺口永远补不回来”的逻辑丢失：

### 1) 客户端只 ACK “已连续收到”的最大 seq（禁止跳跃推进）

- 维护 `deliveredContiguousSeq`：当前会话中“从 1 开始连续收到”的最大 `msgSeq`
- 维护 `readContiguousSeq`：当前会话中“从 1 开始连续已读”的最大 `msgSeq`（且 `readContiguousSeq <= deliveredContiguousSeq`）
- 客户端发送 ACK：
  - `ackType=delivered`：语义上对应推进到 `deliveredContiguousSeq` 所在那条消息
  - `ackType=read`：语义上对应推进到 `readContiguousSeq` 所在那条消息

关键点：

- **可以乱序到达，但只能按连续前缀推进 cursor**：收到 `msgSeq=10` 但缺 `9` 时，不得 ACK 推进到 10。
- **重复/乱序 ACK 可接受**：服务端以 `greatest(old, seq)` 推进，幂等成立。

### 2) 缺口（gap）处理策略（推荐）

当客户端发现会话内存在 gap（例如当前已收到集合里缺少某个 `msgSeq`），应触发补齐，而不是“先跳 ACK”：

- 优先：调用 HTTP 增量拉取（`sinceSeq=deliveredContiguousSeq`）补齐缺口
- 或：触发 WS 兜底补发（RESEND/重连补发），补发同样按 `last_delivered_msg_seq` 区间下发

### 3) 去重与展示排序（与 msgSeq 配套）

- 去重：仍以 `serverMsgId` 为准（保持 cursor 模型的 at-least-once 前提）
- 展示排序：同一会话内按 `msgSeq` 排序；若出现同 `msgSeq`（理论上不应发生，DB 唯一约束保证），按 `serverMsgId` 作为二级排序仅用于兜底

以上约束属于“必要取舍”：你要的是更简单的服务端与更高吞吐，就必须把“按连续前缀推进 ACK + gap 补齐”放到客户端侧实现。

### 历史数据迁移 / 回填策略

迁移目标：

- 为每个会话生成从 1 开始的 `msg_seq`，按 `id`（时间）升序分配。
- 将成员游标从“最大已读/已送达 msgId”转换为对应的 `msgSeq`。

建议做法（MySQL 8）：

1) 为 `t_message` 回填：

- 单聊：`row_number() over (partition by single_chat_id order by id asc)` → `msg_seq`
- 群聊：`row_number() over (partition by group_id order by id asc)` → `msg_seq`

2) 为 `t_single_chat.next_msg_seq / t_group.next_msg_seq` 回填为该会话最大 `msg_seq`。

3) 为成员表回填：

- 对每个 member 的 `last_*_msg_id`，找到同会话内该 `id` 对应的 `msg_seq`，写入 `last_*_msg_seq`。
- 若 member 的 old cursor 为空，则 seq cursor 为空/0。

风险与注意：

- Window 回填对大表会占用较长时间与临时空间；若未来数据量大，需要拆分为离线脚本/分批回填（本仓库当前规模预期可接受）。

## 风险评估

- 数据回填耗时：MySQL window + update 可能在大表上长时间锁/占资源。
- 发送热会话的序列争用：会话级 `UPDATE` 会成为热点；但单聊/小群通常可接受，后续可用“批量预分配 seq 段”优化。
- 接口变更：前端分页参数需同步调整，否则拉取失败。

## 验收标准

- WS 发送消息：落库后 `t_message.msg_seq` 非空，且同一会话严格递增。
- ACK `delivered/read`：成员表 `last_*_msg_seq` 单调递增；重复/乱序 ACK 不影响结果。
- 离线补发：以 `last_delivered_msg_seq` 为边界补发，且不会漏发/重复不会造成 UI 错乱（客户端仍需按 `serverMsgId` 去重）。
- 未读数：与前端展示一致（单聊仅统计发给我的；群聊不统计自己发的；mention 未读与 last_read 同步变化）。
