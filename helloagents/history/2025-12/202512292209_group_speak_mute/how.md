# 技术设计: 群聊禁言（发言限制）

## 技术方案

### 核心技术
- 后端：Spring Boot + MyBatis-Plus
- 网关：Netty WebSocket
- 前端：Vue3 + TypeScript
- 数据库：MySQL + Flyway

### 实现要点
- 数据分层：
  - `t_group_member.mute_until`：用户对群的免打扰（仅屏蔽通知）
  - `t_group_member.speak_mute_until`：管理员对成员的禁言（禁止发送群消息）
- 强制生效点：
  - 在 `WsFrameHandler#handleGroupChat` 中，落库前检查发送者在该群的 `speak_mute_until`，若仍在禁言期则直接返回错误。
- 权限规则：
  - OWNER 可禁言 ADMIN/MEMBER
  - ADMIN 可禁言 MEMBER
  - 禁止禁言 OWNER
  - 禁止禁言自己
- 禁言时长（预设）：
  - 10 分钟 / 1 小时 / 1 天 / 永久 / 解除
  - 服务端只接受预设值，避免客户端随意传入任意时长造成滥用。

## API设计

### [POST] /group/member/mute
- **描述:** 群主管理/管理员对成员设置/解除禁言（发言限制）
- **请求:**
  - `groupId`: long
  - `userId`: long（目标成员）
  - `durationSeconds`: long
    - `0` = 解除禁言
    - `600` = 10分钟
    - `3600` = 1小时
    - `86400` = 1天
    - `-1` = 永久
- **响应:** `Result<Void>`
- **错误码/原因（message 字段）:**
  - `unauthorized`
  - `bad_request`
  - `forbidden`
  - `target_not_member`

## 数据模型

```sql
ALTER TABLE t_group_member
  ADD COLUMN speak_mute_until DATETIME(3) NULL AFTER mute_until;
```

## 安全与性能
- **安全:**
  - 严格身份校验（AuthContext）
  - 严格角色权限校验（OWNER/ADMIN）
  - 入参校验（groupId/userId/durationSeconds）
- **性能:**
  - WS 发送链路只额外做一次成员记录查询（groupId + userId），小群可接受；后续可通过缓存或在 SessionRegistry 中缓存 member state 优化。

## 测试与部署
- **测试:**
  - 本地两用户加入同一群
  - 使用群主/管理员对成员设置禁言
  - 被禁言用户发送 WS `GROUP_CHAT` 应收到 `ERROR group_speak_muted`
  - 解除禁言后可正常发送
- **部署:**
  - Flyway 自动执行 `V5__group_member_speak_mute.sql`
