# Web UI 微调与登录态回收（How）

## 实现要点（以代码为准）
- 好友申请页：
  - “全部”模式不再使用 `→` 文本，改为方向图标 + 两端用户名称
  - 非可操作状态统一为状态胶囊（已添加/已拒绝/待处理）
- 去调试信息：
  - 侧边栏“我的菜单”、设置页、发起单聊弹窗、群资料页、群成员抽屉：移除 `uid/群号` 文案与相关 UI
- 未登录回登录：
  - `frontend/src/services/api.ts` 在 401/业务 40100 且无法 refresh 时，清理登录态并跳转 `/login`

## 变更文件（摘要）
- `frontend/src/views/FriendRequestsView.vue`
- `frontend/src/components/AppLayout.vue`
- `frontend/src/views/SettingsView.vue`
- `frontend/src/components/StartChatModal.vue`
- `frontend/src/views/GroupProfileView.vue`
- `frontend/src/views/GroupChatView.vue`
- `frontend/src/services/api.ts`
- `helloagents/CHANGELOG.md`
- `helloagents/wiki/modules/frontend.md`
- `helloagents/project.md`

