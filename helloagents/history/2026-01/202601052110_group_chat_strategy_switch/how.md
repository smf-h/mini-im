# 群聊策略切换（策略1 + 策略2）- 技术设计

## 1. 总体思路

前提：群成员列表来自 Redis 缓存（Set）`im:cache:group:member_ids:{groupId}`，miss 回读 DB 回填；该能力已在现有代码中落地。

本提案在此基础上新增：

- **批量路由查询**：对成员 `userId` 生成 `im:gw:route:{userId}` key 列表，使用 `MGET`（或 Spring `multiGet`）一次性取回在线路由。
- **按实例分组**：把在线路由按 `serverId` 分桶，形成 `serverId -> List<userId>`。
- **两种策略 + 兜底降级**：
  - 策略1：按实例分组推“消息体”给在线成员。
  - 策略2：按实例分组推“新消息通知”（只含 groupId + latestMsgId/seq 等最小信息），客户端再拉取。
  - 兜底：在线人数超过上限时，不推通知。

所有 Redis/DB IO 不在 Netty `eventLoop` 执行，统一放入 `imDbExecutor`（或专用 `imIoExecutor`）异步执行；写回 Netty Channel 统一切回 `channel.eventLoop()`。

## 2. 策略选择

### 2.1 输入数据
- `groupSize`：群成员数量（来自 Redis Set size 或 DB）。
- `onlineUserCount`：在线用户数（按用户口径去重，来自 `MGET` 结果中存在 route 的 user 数量）。
- `onlineRatio = onlineUserCount / groupSize`（可选）。

### 2.2 默认阈值（可配置）
- `im.group-chat.strategy.group-size-threshold = 2000`
- `im.group-chat.strategy.online-user-threshold = 500`
- `im.group-chat.strategy.notify-max-online-user = 2000`（超过则兜底为不推通知）
- `im.group-chat.strategy.huge-group-no-notify-size = 10000`（可选：超大群直接不做路由批查，直接兜底）

### 2.3 决策伪代码
```
if (groupSize >= hugeGroupNoNotifySize) -> 不推通知（仅落库）
else:
  计算 onlineUserCount（批量路由）
  if (groupSize >= groupSizeThreshold || onlineUserCount >= onlineUserThreshold) -> 策略2（通知后拉取）
  else -> 策略1（按实例分组推消息体）
  if (策略2 && onlineUserCount >= notifyMaxOnlineUser) -> 兜底：不推通知（仅落库）
```

说明：由于不维护群在线索引，onlineUserCount 的计算成本与 groupSize 成正比，因此为超大群提供“直接兜底”的可选阈值，避免每条消息都对 1w 成员做 MGET。

## 3. 策略1：逻辑扇出（按实例分组推消息体）

### 3.1 流程
1) 获取群成员 userIds（Redis Set）。
2) 批量读取在线路由 `MGET im:gw:route:{uid}`。
3) 构建 `serverId -> userIds` 分组（user 口径去重）。
4) **本机 serverId**：直接遍历 userIds，写入本机 Channel。
5) **其它 serverId**：通过 Redis Pub/Sub 控制通道 `im:gw:ctrl:{serverId}` 发送一条“批量 PUSH”消息，payload 含 `userIds[] + envelope`。
6) 发送端不做重试：失败由客户端拉取兜底。

### 3.2 性能要点
- Redis：从 N 次 GET 变成 1 次 MGET + S(MEMBERS/SIZE)。
- 跨实例：从 N 次 publish 变成 “按 serverId 数量”次 publish（每实例可再按批切片）。
- Netty：本机仍需循环写，但只对本机在线用户写；并可按批次/队列在 eventLoop 中分段写以降低尖峰。

## 4. 策略2：读扩散（通知后拉取）

### 4.1 服务端侧
1) 先落库群消息（生成 `groupMsgId/seq`）。
2) 按策略选择判定需要通知时：
   - payload 仅包含 `{groupId, latestMsgId(or seq), senderId, timestamp}` 等最小信息，不包含消息体。
   - 仍按“按实例分组批量 PUSH”下发通知。
3) 若触发兜底（在线过大/超大群），不推通知。

### 4.2 客户端侧（行为约定）
- 收到通知后，按本地 `lastSeq` 发起拉取：`/group/messages?groupId=...&sinceSeq=...`
- 客户端需做去重（以 `seq/msgId` 为准），避免通知与拉取并发导致重复展示。

## 5. 批量 PUSH 协议扩展（向后兼容）

当前控制通道只支持单 user push：`WsClusterMessage.userId + envelope`。

扩展为：
- `userId`（可选，旧版字段保留）
- `userIds`（可选，新字段；非空表示批量）

监听端处理：
- 若 `userIds` 非空：遍历并写到本机 user 的 Channel。
- 否则按原逻辑处理单 user。

## 6. 风险与规避
- **路由键过多导致 Redis 大包**：对 `userIds` 按批次（默认 500）切片做 MGET/分组处理。
- **通知风暴**：通过 `notifyMaxOnlineUser` 与 `hugeGroupNoNotifySize` 兜底降级。
- **乱序**：策略1/2 都可能受到线程池与异步 IO 影响；可与“per-channel Future 链”方案包配合，在同一发送者维度减少乱序。

