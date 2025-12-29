# 任务清单：申请入群 + 个人主页 + FriendCode/GroupCode

任务：
- [√] DB：为 `t_user` 增加 `friend_code/friend_code_updated_at`（唯一索引）
- [√] DB：为 `t_group` 增加 `group_code/group_code_updated_at`（唯一索引）
- [√] DB：新增 `t_group_join_request`（申请入群表）并加入 Flyway 迁移
- [√] 后端：新增 FriendCode 生成/重置/解析服务（含限频与配置项）
- [√] 后端：新增 GroupCode 生成/重置/解析服务（含限频与配置项）
- [√] 后端：新增入群申请 API（request/list/decide）与权限校验（owner/admin）
- [√] 后端：补齐群管理 API（成员列表、踢人、设管理员、转让群主、退出群）
- [√] 后端：新增 `WsPushService` 推送 `GROUP_JOIN_REQUEST/GROUP_JOIN_DECISION`（best-effort）
- [√] 后端：新增用户个人主页 API（`/user/profile`、`/me/profile`）
- [√] 后端：好友申请改造为 FriendCode（WS/HTTP 其一为主，另一为兜底）
- [√] 前端：新增 `UserProfileView`（头像可点进入）
- [√] 前端：新增 `GroupProfileView`（群资料/群码/成员/管理/申请）
- [√] 前端：好友申请页改为 FriendCode 输入；个人主页增加“一键申请好友”
- [√] 前端：群聊页增加“群资料”按钮；群列表页增加“群码申请加入”
- [√] 文档：更新 `helloagents/wiki/api.md`、`helloagents/wiki/data.md`、`helloagents/wiki/modules/frontend.md`、`helloagents/CHANGELOG.md`
- [√] 验证：`mvn test`、`npm -C frontend run build`
