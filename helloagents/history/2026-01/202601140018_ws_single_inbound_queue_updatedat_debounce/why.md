# 变更提案: 单聊尾延迟收敛（入站队列上限 + 会话 updatedAt 去抖）

## 需求背景

单聊链路在高并发压测下的主要问题不是“单条 SQL 很慢”，而是**排队时间（queueing delay）占主导**：

- `ws_perf single_chat.queueMs` 体现 per-connection 串行队列排队（`WsChannelSerialQueue`）。
- `ws_perf single_chat.dbQueueMs` 体现 DB 线程池排队（DB executor queue wait）。

在现有实测中（5 实例/5000 clients），`queueMs` 与 `dbQueueMs` 的 P95/P99 可达秒级甚至更高，而 `saveMsgMs/updateChatMs` 本身多为十几毫秒量级，说明“系统在过载时没有足够强的门禁与写放大控制”，导致尾延迟被队列放大。

已确认的业务取舍与前提：
- 客户端对 `ERROR reason=server_busy` 采用**退避 + 抖动**重试（可接受小比例失败换稳定）。
- 会话列表排序/置顶依赖 `t_single_chat.updated_at`，但允许**最多约 1s 的延迟**（最终一致即可）。

## 变更内容

1. **入站队列上限（连接级门禁）**
   - 对每条 WS 连接的 `WsChannelSerialQueue` pending 任务数设置硬上限。
   - 超过上限时快速返回 `ERROR reason=server_busy`，避免无限排队导致 P95/P99 爆炸。

2. **会话 updatedAt 去抖（降低 DB 写放大）**
   - 当前每条单聊消息都会执行 `UPDATE t_single_chat SET updated_at=now`，属于典型写放大。
   - 改为对同一个 `singleChatId` **最多每 1s 更新一次 updated_at**，把“每条消息一写”降低为“每会话每窗口一写”。

## 影响范围

- **模块:**
  - `gateway/ws`（入站队列门禁、单聊处理链路）
  - `domain/single_chat`（会话 updatedAt 更新策略）
- **数据:**
  - `t_single_chat.updated_at` 更新频率降低（≤1s 级延迟，最终一致）
- **API:**
  - 无新增接口；客户端需正确处理 `server_busy`（已确认具备退避 + 抖动）

## 核心场景

### 需求: 压低单聊尾延迟（P95/P99 收敛）
**模块:** gateway/ws + domain

#### 场景: 过载时“可控失败”而非“无限排队”
- 条件：单连接或整体负载过高（客户端持续发消息、inflight 增大、或 DB 写入接近饱和）
- 预期：
  - `queueMs` 不出现线性爬升；P95/P99 明显收敛
  - `server_busy` 失败比例可控（目标 < 5%）
  - 系统整体可用性更稳定（吞吐与延迟波动降低）

### 需求: 会话列表置顶允许 1s 内最终一致
**模块:** domain/single_chat

#### 场景: 高频单聊不再“每条消息都写会话表”
- 条件：同一会话短时间内连续产生多条消息（burst）
- 预期：
  - `t_single_chat.updated_at` 更新频率降低到约 1 次/秒/会话（或更少）
  - 会话列表置顶延迟 ≤ 1s（业务可接受范围）

## 风险评估

- **风险:** `server_busy` 增多可能引发客户端重试风暴
  - **缓解:** 客户端已采用退避 + 抖动；服务端门禁应“越忙越早拒绝”，避免排队放大。
- **风险:** 会话列表更新存在 ≤1s 延迟，极端情况下排序短暂滞后
  - **缓解:** 已确认业务允许；并且只对 burst 场景生效，低频场景几乎不改变行为。

