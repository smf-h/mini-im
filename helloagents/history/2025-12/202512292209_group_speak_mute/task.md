# 任务清单: 群聊禁言（发言限制）

目录: `helloagents/plan/202512292209_group_speak_mute/`

---

## 1. 数据库与实体
- [√] 1.1 新增 Flyway migration：`src/main/resources/db/migration/V5__group_member_speak_mute.sql`，验证 why.md#需求-群聊禁言-场景-群主管理员禁言成员预设时长
- [√] 1.2 更新 `src/main/resources/db/schema-mysql.sql` 增加 `speak_mute_until`，与迁移保持一致
- [√] 1.3 更新 `src/main/java/com/miniim/domain/entity/GroupMemberEntity.java` 增加字段映射

## 2. 后端接口与权限
- [√] 2.1 在 `src/main/java/com/miniim/domain/service/GroupManagementService.java` 增加禁言能力接口
- [√] 2.2 在 `src/main/java/com/miniim/domain/service/impl/GroupManagementServiceImpl.java` 实现权限校验与更新逻辑
- [√] 2.3 在 `src/main/java/com/miniim/domain/controller/GroupProfileController.java` 增加 `POST /group/member/mute` 接口
- [√] 2.4 在成员列表 DTO 中补充 `speakMuteUntil`（用于前端展示）

## 3. WS 群聊发送链路禁言校验
- [√] 3.1 在 `src/main/java/com/miniim/gateway/ws/WsFrameHandler.java` 的 `handleGroupChat` 落库前增加禁言检查，返回 `ERROR reason=group_speak_muted`

## 4. 前端联动
- [√] 4.1 更新 `frontend/src/types/api.ts` 的群成员 DTO 增加 `speakMuteUntil`
- [√] 4.2 更新 `frontend/src/views/GroupProfileView.vue`：成员菜单增加禁言/解除禁言按钮（10m/1h/1d/forever）
- [√] 4.3 更新 `frontend/src/views/GroupChatView.vue`：被禁言时提示并禁止发送；发送失败时友好提示

## 5. 安全检查
- [√] 5.1 执行安全检查（输入校验、权限控制、避免信任客户端 from/userId）

## 6. 文档更新
- [√] 6.1 更新 `helloagents/wiki/api.md`、`helloagents/wiki/data.md`、`helloagents/wiki/modules/*` 记录禁言能力
- [√] 6.2 更新 `helloagents/CHANGELOG.md`

## 7. 测试
- [√] 7.1 `mvn test`
- [√] 7.2 `npm -C frontend run build`
