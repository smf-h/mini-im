# 任务清单: 会话免打扰（DND）

目录: `helloagents/plan/202512291142_dnd/`

---

## 1. 前端 Store（DND）
- [√] 1.1 新增 `frontend/src/stores/dnd.ts`：按 `userId` 持久化 dm/group 免打扰配置，验证 why.md#需求-按会话免打扰开关-场景-刷新后仍保持
- [√] 1.2 在登录后调用 `dnd.hydrate()`（或在 store 内监听 auth），验证 why.md#需求-按会话免打扰开关-场景-刷新后仍保持

## 2. toast 免打扰拦截
- [√] 2.1 在 `frontend/src/components/AppLayout.vue` 的 toast 触发逻辑中接入 DND 判定，验证 why.md#需求-按会话免打扰开关-场景-单聊开启免打扰
- [√] 2.2 在 `frontend/src/components/AppLayout.vue` 中确保 `GROUP_CHAT important=true` 仍弹 toast，验证 why.md#需求-按会话免打扰开关-场景-群聊开启免打扰

## 3. UI：聊天页开关 + 会话列表标识
- [√] 3.1 在 `frontend/src/views/ChatView.vue` 增加免打扰开关（只影响该 peerUserId），验证 why.md#需求-按会话免打扰开关-场景-单聊开启免打扰
- [√] 3.2 在 `frontend/src/views/GroupChatView.vue` 增加免打扰开关（只影响该 groupId），验证 why.md#需求-按会话免打扰开关-场景-群聊开启免打扰
- [√] 3.3 在 `frontend/src/views/ChatsView.vue` 会话列表展示免打扰标识与快捷切换，验证 why.md#需求-按会话免打扰开关-场景-单聊开启免打扰 与 why.md#需求-按会话免打扰开关-场景-群聊开启免打扰

## 4. 安全检查
- [√] 4.1 检查 localStorage 不落敏感信息、输入参数均为字符串 id，符合 G9

## 5. 后端：DND API + 存储
- [√] 5.1 新增 `GET /dnd/list`：返回当前用户已 mute 的单聊 peerUserIds 与群聊 groupIds
- [√] 5.2 新增 `POST /dnd/dm/set`：写入/清除 `t_single_chat_member.mute_until`（仅本人侧）
- [√] 5.3 新增 `POST /dnd/group/set`：写入/清除 `t_group_member.mute_until`（仅本人侧，需校验群成员）
- [√] 5.4 前端切换开关时调用服务端写入，失败回滚并提示

## 6. 文档更新
- [√] 6.1 更新 `helloagents/wiki/modules/frontend.md` 补充 DND（含服务端同步策略）
- [√] 6.2 更新 `helloagents/wiki/api.md` 增补 DND API
- [√] 6.3 更新 `helloagents/CHANGELOG.md` 记录 DND 功能

## 7. 测试
- [√] 7.1 执行 `npm -C frontend run build` 验证编译通过
- [√] 7.2 执行 `mvn test`（或至少编译通过）验证后端改动无语法错误
