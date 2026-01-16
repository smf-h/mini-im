# 技术设计: 单聊尾延迟收敛（入站队列上限 + 会话 updatedAt 去抖）

## 技术方案

### 方案概述（推荐）

本方案遵循“先把过载变可控，再减少写放大”的顺序：

1) **连接级入站门禁**：把背压门禁前置到 `WsChannelSerialQueue` 入队处，限制每连接 pending 数，过载快速 `server_busy`。

2) **会话 updatedAt 去抖**：对 `singleChatId` 做 1s 窗口去抖，减少 `UPDATE t_single_chat` 的写放大，从而降低 DB executor 占用与 `dbQueueMs`。

### 备选方案对比

#### 方案A（推荐）：应用侧去抖（每实例）+ 1s 窗口
- **做法:** 在服务端维护 `singleChatId -> lastUpdatedAtWriteMs`，同一会话 1s 内仅执行一次 `UPDATE t_single_chat.updated_at`。
- **优点:** 真正减少 DB 更新语句次数；改动小；对 burst 场景收益直接。
- **缺点:** 多实例下同一会话可能在不同实例各写一次（仍远小于“每条消息一写”）。

#### 方案B：SQL 条件更新（每条消息仍发 UPDATE）
- **做法:** `UPDATE ... WHERE id=? AND updated_at < now-1s`。
- **优点:** 代码最小改动。
- **缺点:** 每条消息仍会打到 DB（语句次数不降），对 `dbQueueMs` 改善不确定；不推荐作为主方案。

#### 方案C：异步批量 flush（窗口聚合后批量更新）
- **做法:** 收集窗口内变更会话，周期性批量写回。
- **优点:** 写入次数最低，可做批量/合并。
- **缺点:** 复杂度更高（调度/失败重试/线程隔离/观测）；超出“最小改动”优先级。

## 实现要点

### 1) 入站队列上限（连接级门禁）

- **现状位置：** `WsSingleChatHandler.handle` 里使用 `WsChannelSerialQueue.tryEnqueue/enqueue` 入队（并记录 `queueMs`）。
- **配置：** `WsInboundQueueProperties`（`im.gateway.ws.inbound-queue.enabled/max-pending-per-conn`）。
- **推荐策略：**
  - 压测/灰度先从 `max-pending-per-conn=4` 开始，观察 `wsErrorRate` 与 `queueMs` 分位数。
  - 若 P95/P99 仍不收敛，可逐步收紧至 `2/1`；客户端依赖退避 + 抖动避免重试风暴。
- **预期收益：** `queueMs` 的 P95/P99 显著下降；尾延迟更稳定；代价是 `server_busy` 增多（可控）。

### 2) t_single_chat.updated_at 去抖（1s 窗口）

- **现状位置：** 单聊落库任务（DB executor）内，每条消息均执行一次 `singleChatService.update(...set updatedAt...)`。
- **写放大解释：**
  - 在 burst（同一会话高频）场景下，每条消息都更新会话表会造成额外写 IO、索引维护与锁竞争，放大 DB 排队。
  - 去抖后“每会话每秒最多一次更新”，通常能显著降低写放大与 DB executor 占用。
- **推荐实现（方案A）：**
  - 在服务端增加一个轻量去抖器（每实例），以 `singleChatId` 为 key。
  - 每条消息保存成功后，判断是否满足 `nowMs - lastWriteMs >= windowMs(=1000)`：
    - 是：执行 `UPDATE t_single_chat.updated_at` 并更新 lastWriteMs
    - 否：跳过本次更新（不影响消息落库与 ACK(saved)）
- **一致性说明：**
  - 会话列表依赖 `updated_at`，会出现 ≤1s 的置顶延迟（已确认可接受）。
  - 多实例下同一会话可能被多个实例各更新一次/秒，属于可接受的“最多 N 次/秒”，仍显著少于高频消息的“每条一写”。

## 数据模型

相关表（MySQL，Flyway V1）：
- `t_message`：单聊消息落库（每条 1 次 INSERT）
- `t_single_chat`：会话（当前每条消息 1 次 UPDATE，拟去抖）
- `t_single_chat_member`：成员游标（ensureMembers 可能触发 EXISTS/INSERT）

## 安全与性能

- **安全:** 无新增权限/敏感信息；`server_busy` 属于过载保护，不返回内部细节。
- **性能:** 优先压制排队（queueing delay）与 DB 写放大；对 P95/P99 更敏感，且能把过载从“拖垮”变成“可控拒绝”。

## 测试与部署

### 回归测试（建议）

1) **高压 burst 场景（验证去抖收益）**
- 参数示例：`clients=5000`，`msgIntervalMs=100`（每会话 10 msg/s），`inflight>=4`
- 关注：`ws_perf single_chat.dbQueueMs/queueMs/updateChatMs` 与 `wsErrorRate` 的变化趋势

2) **常规负载场景（验证不回退）**
- 参数示例：`clients=5000`，`msgIntervalMs=3000`（每会话 0.33 msg/s）
- 关注：E2E 分位数、`wsErrorRate`、`deliver%` 是否与历史一致或更稳

3) **门禁敏感性对照**
- 对照 `max-pending-per-conn=4/2/1`，观察尾延迟收敛与 `server_busy` 比例是否在可接受阈值内

