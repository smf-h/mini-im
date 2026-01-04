# 变更提案: 会话免打扰（DND）

## 需求背景
当前前端会对新消息弹出 toast 提醒。用户希望支持“免打扰”，并且是**按会话**开启：开启后只屏蔽该会话的**普通消息**弹窗；对 `@我/important` 仍提示；免打扰不对对方产生任何可见通知或行为改变。

同时，免打扰需要支持**跨端一致性**（服务端也存一份），前端保留本地缓存用于快速启动与离线兜底。

## 变更内容
1. 新增“按会话免打扰”开关（单聊/群聊分别独立配置）
2. toast 触发前增加免打扰判断：普通消息命中免打扰则不弹；`important=true` 的群聊提醒不受影响
3. 会话列表展示免打扰标识，并提供快捷入口，避免用户遗忘
4. 免打扰配置服务端持久化（以 `userId` 维度隔离），前端本地缓存（localStorage）用于启动加速与兜底

## 影响范围
- **模块:** frontend + backend
- **文件:** `frontend/src/components/AppLayout.vue`、`frontend/src/views/ChatsView.vue`、`frontend/src/views/ChatView.vue`、`frontend/src/views/GroupChatView.vue`、`frontend/src/stores/*`（预计）、`src/main/java/com/miniim/domain/controller/*`（预计）、`src/main/java/com/miniim/domain/service/*`（预计）
- **API:** 新增 DND API（仅存储/同步，不改变消息收发与投递）
- **数据:** 复用 `t_single_chat_member.mute_until` 与 `t_group_member.mute_until`（不新增表）

## 核心场景

### 需求: 按会话免打扰开关
**模块:** frontend
为单聊与群聊会话提供免打扰开关与持久化。

#### 场景: 单聊开启免打扰
用户在单聊聊天窗口开启免打扰。
- 后续来自该用户的普通消息不再弹 toast
- 会话列表显示免打扰标识
- `@我/important`（本需求中单聊没有 important）不涉及

#### 场景: 群聊开启免打扰
用户在群聊聊天窗口开启免打扰。
- 群内普通消息不再弹 toast
- 但 `important=true` 的群聊消息仍弹 toast
- 会话列表显示免打扰标识

#### 场景: 刷新后仍保持
用户刷新页面或重新进入站点。
- 免打扰状态保持不变（优先服务端，前端 localStorage 兜底）

#### 场景: 多端一致
用户在 A 设备打开某会话免打扰，随后在 B 设备登录。
- B 设备可拉取到相同免打扰配置（服务端存储）

## 风险评估
- **风险:** 引入服务端配置与 API 后，需要处理权限校验与数据一致性
- **缓解:** 复用成员表 `mute_until` 字段，不新增表；仅允许本人修改自己的 mute；失败时前端回退到本地缓存并提示
