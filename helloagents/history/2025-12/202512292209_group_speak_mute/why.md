# 变更提案: 群聊禁言（发言限制）

## 需求背景
当前系统已支持“免打扰”（本质是屏蔽通知），但缺少“禁言”（禁止在群里发送消息）的能力。需要支持群主管理与管理员管理成员发言，并支持预设时长禁言。

## 变更内容
1. 在群成员维度新增“禁言到期时间”，用于控制是否允许发言。
2. 提供群管理接口：对成员设置/解除禁言（预设时长）。
3. 在 WS 群聊发送链路增加禁言校验：被禁言用户发送群消息直接拒绝，不落库、不推送。
4. 前端在群资料页提供禁言操作入口，并在群聊页提示“已被禁言”与到期时间。

## 影响范围
- **模块:**
  - gateway（WS 群聊消息入口）
  - domain（群成员/群管理接口）
  - frontend（群资料页/群聊页）
- **文件:**
  - `src/main/resources/db/migration/V5__group_member_speak_mute.sql`
  - `src/main/resources/db/schema-mysql.sql`
  - `src/main/java/com/miniim/domain/entity/GroupMemberEntity.java`
  - `src/main/java/com/miniim/domain/service/GroupManagementService.java`
  - `src/main/java/com/miniim/domain/service/impl/GroupManagementServiceImpl.java`
  - `src/main/java/com/miniim/domain/controller/GroupProfileController.java`
  - `src/main/java/com/miniim/gateway/ws/WsFrameHandler.java`
  - `frontend/src/types/api.ts`
  - `frontend/src/views/GroupProfileView.vue`
  - `frontend/src/views/GroupChatView.vue`
  - `helloagents/wiki/api.md`
  - `helloagents/wiki/data.md`
  - `helloagents/wiki/modules/gateway.md`
  - `helloagents/wiki/modules/domain.md`
  - `helloagents/wiki/modules/frontend.md`
- **API:**
  - `POST /group/member/mute`（新增）
- **数据:**
  - `t_group_member` 新增列：`speak_mute_until`

## 核心场景

### 需求: 群聊禁言
**模块:** domain / gateway / frontend
为群主管理/管理员提供对群成员禁言的能力，并在发送链路强制生效。

#### 场景: 群主/管理员禁言成员（预设时长）
群主/管理员在群资料页对成员选择“禁言 10 分钟 / 1 小时 / 1 天 / 永久 / 解除禁言”。
- 服务端按权限规则更新成员 `speak_mute_until`
- 群资料页展示成员禁言状态

#### 场景: 被禁言成员尝试发送群消息
被禁言成员在群聊页点击发送。
- WS `GROUP_CHAT` 被拒绝，返回 `ERROR reason=group_speak_muted`
- 消息不落库、不推送
- 前端提示“你已被禁言”，并展示禁言到期时间

#### 场景: 禁言到期后恢复发言
到期时间小于等于当前时间后，成员正常发言。
- 发送链路放行

## 风险评估
- **风险:** 混淆“免打扰（mute_until）”与“禁言（speak_mute_until）”语义
  - **缓解:** 数据字段与校验逻辑完全分离；禁言只影响“发送群消息”，免打扰只影响“是否 toast”
- **风险:** 权限校验不足导致越权禁言
  - **缓解:** 复用现有群角色规则：OWNER 可禁言 ADMIN/MEMBER；ADMIN 仅可禁言 MEMBER；禁止禁言 OWNER；禁止禁言自己
