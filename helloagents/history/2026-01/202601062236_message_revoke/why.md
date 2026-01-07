# 变更提案: 消息撤回（2分钟，仅发送者）

## 需求背景

当前消息链路已具备：
- WS 实时收发（`SINGLE_CHAT` / `GROUP_CHAT`）
- ACK（落库确认/送达/已读）与离线补发
- HTTP 历史查询（单聊/群聊）与会话列表（包含 lastMessage）

缺失能力：发送者在短时间内撤回已发送消息，且撤回后客户端展示“已撤回”，同时不再对外暴露原文内容。

## 产品分析

### 目标用户与场景
- **目标用户:** 需要纠错/发错对象/临时敏感信息误发的普通用户
- **使用场景:** 发送后立刻发现错误，在短时间内撤回并降低传播
- **核心痛点:** 误发后无法快速补救；撤回后仍可能在历史/会话 lastMessage/离线补发中泄露原文

### 价值主张与成功指标
- **价值主张:** 提供可预期、可解释的“短时撤回”能力，减少误发成本
- **成功指标:**
  - 撤回权限与时限校验正确（仅发送者，2分钟）
  - 撤回后各入口展示一致（WS/HTTP/离线补发均显示“已撤回”，不返回原文）
  - 撤回操作具备幂等性（重复撤回不造成异常与多次状态推进）

### 人文关怀
- 仅在短时间窗口允许撤回，减少“事后篡改对话”的风险
- 服务端保留原文但不再对外暴露，兼顾纠纷排查与隐私边界

## 变更内容

1. 增加 WS 撤回指令与撤回广播事件（单端在线场景优先）
2. 服务端将消息状态推进为 `REVOKED`，并对外统一做内容脱敏（显示“已撤回”）
3. 补齐 HTTP 历史与会话 lastMessage 的撤回展示一致性

## 影响范围

- **模块:**
  - gateway（WS 协议、路由、推送）
  - domain（消息状态更新、会话更新时间、查询输出）
  - frontend（消息气泡展示、撤回入口、撤回事件处理）
  - helloagents/wiki（API/模块文档同步）
- **文件（预估）:**
  - `src/main/java/com/miniim/gateway/ws/WsFrameHandler.java`
  - `src/main/java/com/miniim/gateway/ws/WsEnvelope.java`
  - `src/main/java/com/miniim/gateway/ws/WsResendService.java`
  - `src/main/java/com/miniim/domain/entity/MessageEntity.java`
  - `src/main/java/com/miniim/domain/enums/MessageStatus.java`
  - `src/main/java/com/miniim/domain/controller/SingleChatMessageController.java`
  - `src/main/java/com/miniim/domain/controller/GroupMessageController.java`
  - `src/main/java/com/miniim/domain/controller/SingleChatConversationController.java`
  - `src/main/java/com/miniim/domain/controller/GroupConversationController.java`
  - `frontend/src/views/*ChatView.vue`、`frontend/src/stores/ws.ts`（以实际实现为准）
- **API:**
  - WS 新增：`MESSAGE_REVOKE` / `MESSAGE_REVOKED`（命名可在实现阶段最终定稿）
- **数据:**
  - 仅更新 `t_message.status`（不新增表/不改列）；原文仍保留在 `t_message.content`

## 核心场景

### 需求: 撤回单聊消息
**模块:** gateway / domain / frontend

#### 场景: 发送者在 2 分钟内撤回成功
- 条件：当前用户是消息发送者；消息创建时间 ≤ 2 分钟；消息存在且未撤回
- 预期结果：
  - 服务端将消息状态更新为 `REVOKED`
  - 发送者收到撤回成功确认（ACK 或单独事件）
  - 接收方在线时收到撤回通知并将该条消息展示为“已撤回”
  - 后续 HTTP 拉取/会话 lastMessage/离线补发均展示“已撤回”，不返回原文

#### 场景: 超时撤回失败
- 条件：消息创建时间 > 2 分钟
- 预期结果：服务端拒绝并返回可解释的错误原因（如 `revoke_timeout`）

#### 场景: 非发送者尝试撤回失败
- 条件：当前用户不是消息发送者
- 预期结果：服务端拒绝并返回错误原因（如 `not_message_sender`）

#### 场景: 重复撤回幂等
- 条件：消息已处于 `REVOKED`
- 预期结果：返回“已撤回”成功语义，不产生二次副作用

### 需求: 撤回群聊消息
**模块:** gateway / domain / frontend

#### 场景: 发送者在 2 分钟内撤回成功并广播
- 条件：当前用户是消息发送者；消息属于该群；消息创建时间 ≤ 2 分钟
- 预期结果：
  - 服务端将消息状态更新为 `REVOKED`
  - 群内在线成员收到撤回通知并展示“已撤回”
  - 后续 HTTP 拉取/会话 lastMessage/离线补发均展示“已撤回”，不返回原文

## 风险评估
- **风险:** 撤回后历史接口仍返回原文导致信息泄露
  - **缓解:** 在 HTTP 返回与 WS 投递/补发层统一做“撤回内容脱敏”
- **风险:** 只更新状态但未通知对端导致 UI 不一致
  - **缓解:** WS 推送撤回事件；离线端通过后续 HTTP 拉取看到一致结果
- **风险:** 时间判断口径不一致（createdAt 时区/精度）
  - **缓解:** 以服务端时间为准，统一实现时间窗口判断并加测试覆盖
