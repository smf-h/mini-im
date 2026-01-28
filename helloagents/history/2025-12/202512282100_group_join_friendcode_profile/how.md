# 技术设计：申请入群 + 个人主页 + FriendCode/GroupCode

## 1. 核心模型与字段

### 1.1 FriendCode（用户码）
- 存储在 `t_user.friend_code`（唯一、不可枚举）。
- 增加 `t_user.friend_code_updated_at` 用于限频重置。
- 生成：随机码（建议 8~10 位，`A-Z0-9`），冲突则重试；返回/展示为字符串。

### 1.2 GroupCode（群码）
- 存储在 `t_group.group_code`（唯一、不可枚举）。
- 增加 `t_group.group_code_updated_at` 用于限频重置。
- 展示策略：
  - 群资料页对“所有登录用户”可见（满足产品需求），但需要通过 `groupId` 或 `groupCode` 访问页面。

### 1.3 入群申请表
新增 `t_group_join_request`：
- `id`（PK）
- `group_id`
- `from_user_id`（申请者）
- `message`（验证信息，可空）
- `status`（PENDING/ACCEPTED/REJECTED/CANCELED）
- `handled_by`、`handled_at`
- `created_at`、`updated_at`

关键索引：
- `(group_id, status, id)`：管理员查看待处理
- `(from_user_id, status, id)`：申请者查看结果/历史
- `(group_id, from_user_id, status)`：防止重复申请（PENDING 状态幂等）

## 2. 权限模型

沿用 `t_group_member.role`：
- `1=owner`：可做所有管理动作（审批/踢人/设管理员/转让群主/重置群码）
- `2=admin`：可审批入群；可踢 `member`；不可踢 `owner/admin`；不可转让群主
- `3=member`：可查看群资料与成员；可退出群

## 3. API 设计（HTTP）

### 3.1 用户
- `GET /me/profile`：返回我的公开资料 + `friendCode` + `friendCodeUpdatedAt`
- `POST /me/friend-code/reset`：重置 FriendCode（服务端限频）
- `GET /user/profile?userId=...`：返回用户公开资料（nickname/username/avatar/status/friendCode）

### 3.2 好友申请（改造）
- `POST /friend/request/by-code`（或保持 WS 但提供 HTTP 兜底）：
  - 入参：`toFriendCode`、`message`
  - 行为：解析 code → toUserId → 复用现有 friend request 落库逻辑

### 3.3 群资料与成员
- `GET /group/profile?groupCode=...`：通过群码获取群基础信息（用于“查群/申请入群”）
- `GET /group/profile/by-id?groupId=...`：通过 id 获取群资料（群聊页入口）
- `GET /group/member/list?groupId=...`：成员列表（需要是群成员；或对外仅返回 memberCount）
- `POST /group/leave`：成员退出

### 3.4 群码与管理
- `POST /group/code/reset`：重置群码（owner/admin，限频）
- `POST /group/member/kick`：踢人（owner/admin，按权限规则）
- `POST /group/member/set-admin`：设/取消管理员（owner）
- `POST /group/owner/transfer`：转让群主（owner）

### 3.5 入群申请
- `POST /group/join/request`：通过群码发起申请（幂等：PENDING 不重复插）
- `GET /group/join/requests?groupId=...&status=pending&lastId=...`：管理员查看申请列表
- `POST /group/join/decide`：审批（owner/admin），通过后写入 `t_group_member`，并 WS 通知申请者

## 4. WS 通知（体验增强）

新增服务端主动推送事件（仅用于通知；核心状态以 DB 为准）：
- `GROUP_JOIN_REQUEST`：推送给群主/管理员（有人申请入群）
- `GROUP_JOIN_DECISION`：推送给申请者（同意/拒绝结果）
- `GROUP_MEMBER_CHANGED`（可选）：成员变更通知（踢人/退出/入群）

实现方式：
- 新增 `WsPushService`（Spring `@Component`）：注入 `SessionRegistry` + `ObjectMapper`，对当前实例在线用户 best-effort 推送。
- 多实例场景：推送可能丢失，但管理员/申请者可通过 HTTP 拉取补齐。

## 5. 前端改造

### 5.1 头像可点 + 个人主页
- 使用现有 `UiAvatar` 组件替换占位头像；点击跳转 `/u/:userId`
- 新增 `UserProfileView`：展示 avatar/nickname/username/status/friendCode；按钮“申请好友”

### 5.2 加好友入口改造
- 好友申请页：将 “toUserId” 输入替换为 “FriendCode” 输入
- 支持从个人主页一键发起好友申请（复用同一 API/WS）

### 5.3 群资料与入群申请
- 群聊页增加“群资料”按钮 → `/group/:groupId/profile`
- 群资料页：
  - 展示群名/群码/成员列表（含角色），并提供管理按钮（踢人/设管理员/转让群主/重置群码）
  - 仅成员可看成员列表与管理按钮
- 群列表页增加“通过群码申请加入”输入与申请按钮（进入群资料/申请页）

## 6. 可配置项（默认值）
- FriendCode/GroupCode 长度：默认 8
- 重置冷却：默认 24h（可在 `application.yml` 配置）
- 入群申请 message 最大长度：默认 256

