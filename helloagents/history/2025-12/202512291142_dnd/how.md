# 技术设计: 会话免打扰（DND）

## 技术方案

### 核心技术
- Vue3 + TypeScript + Pinia
- localStorage 持久化（前端缓存/兜底）
- Spring Boot + MyBatis-Plus（服务端配置存储与同步）

### 实现要点
- 新增前端 store：`dnd`（Do Not Disturb）
  - key 结构：`dnd:v1:${userId}`，值为 `{ dm: Record<peerUserId, true>, group: Record<groupId, true> }`
  - API：
    - `isDmMuted(peerUserId)` / `toggleDm(peerUserId)` / `setDm(peerUserId, muted)`
    - `isGroupMuted(groupId)` / `toggleGroup(groupId)` / `setGroup(groupId, muted)`
    - `hydrate()`：
      - 先从 localStorage 读取快速渲染
      - 登录后调用服务端 `GET /dnd/list` 覆盖为服务端最新配置
      - 网络失败时保留本地缓存（兜底）
- toast 侧拦截（核心行为）
  - `SINGLE_CHAT`：
    - 若命中 `dmMuted[fromUserId]==true` → 不弹 toast
  - `GROUP_CHAT`：
    - 若 `important=true` → 永远允许 toast（符合“不屏蔽 important”）
    - 否则若命中 `groupMuted[groupId]==true` → 不弹 toast
- UI
  - 聊天窗口（单聊/群聊）顶部 header 添加免打扰开关（开/关）
  - 会话列表（单聊/群聊列表项右侧）展示一个细小的免打扰标识（如 “🔕”），并在列表项内提供快捷切换按钮（避免误触，按钮点击需 stopPropagation，不能打开会话）

## 架构决策 ADR
### ADR-001: 服务端存储 + 前端本地缓存（推荐）
**上下文:** 免打扰只影响“是否弹 toast”，不影响消息收发；但需要跨端一致（服务端也存一份）。
**决策:**
1. 服务端复用现有成员表字段存储 mute：`t_single_chat_member.mute_until`、`t_group_member.mute_until`
2. 前端继续用 localStorage 做缓存/兜底：`dnd:v1:${userId}`
3. 前端切换开关时：先本地生效（优化体验），再调用服务端写入；写入失败则回滚并提示
**理由:** 不新增表、与“位点模型”一致；跨端一致；本地仍能快速启动。
**影响:** 新增 DND API；需要在服务端做权限校验（只能修改本人所属会话/群的 mute）。

## API设计
- `GET /dnd/list`
  - 说明：返回当前用户已开启免打扰的会话列表（单聊返回 peerUserIds，群聊返回 groupIds）
- `POST /dnd/dm/set`
  - 入参：`{ peerUserId, muted }`
  - 说明：设置与某个用户的单聊免打扰（仅修改“自己”那一侧）
- `POST /dnd/group/set`
  - 入参：`{ groupId, muted }`
  - 说明：设置某个群的免打扰（仅修改“自己”那一侧）

## 数据模型
- 复用字段：
  - `t_single_chat_member.mute_until`：单聊成员的 mute 截止时间；开启时写入一个“足够远的时间”（等价长期 mute），关闭时置空
  - `t_group_member.mute_until`：群成员的 mute 截止时间；同上

## 安全与性能
- **安全:**
  - 服务端：仅允许本人修改自己的 mute；群聊需校验是群成员
  - 前端：localStorage 不存敏感信息，仅保存会话 id 的布尔状态
- **性能:**
  - 前端：内存 cache + 事件驱动写入，避免频繁 JSON 序列化
  - 服务端：list 查询仅按 `user_id + mute_until` 命中过滤索引（或轻量扫描），且数据量与用户参与会话规模相关

## 测试与部署
- **测试:** `npm -C frontend run build`、`mvn test`（或至少编译通过）；手工验证：
  - 对某会话开启免打扰后，普通消息不弹 toast
  - 群聊 `important=true` 仍弹 toast
  - 在另一设备/浏览器登录能同步免打扰状态
- **部署:** 前端构建产物发布；后端按常规发布（无 DB 新增迁移）
