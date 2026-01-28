# 变更提案: Redis 缓存（三项）- 个人信息 / 群基本信息 / 单聊会话映射

## 需求背景
当前项目已经依赖 Redis（refreshToken、WS 路由等），但在“读多写少、可缓存”的查询路径上仍以 DB 直读为主：
- `/me/profile` 需要读取用户信息并可能补齐 friendCode
- `/group/profile/*` 需要读取群信息与成员数，并返回“与当前用户相关”的字段
- WS 单聊发送中 `getOrCreateSingleChatId(user1,user2)` 会触发 DB 查询与潜在插入

在并发与多实例部署下，这些热点读会带来 DB 压力，并放大重连/重试时的峰值。

## 变更内容
仅聚焦前三个低复杂度缓存目标（不包含群成员列表/群在线人）：
1. **个人信息缓存**：缓存用户资料（昵称/头像/状态/friendCode 等），降低 `/me/profile` DB 读取频次。
2. **群基本信息缓存**：缓存群的静态字段与 memberCount，避免把“与请求者相关字段”混入全局缓存。
3. **单聊会话映射缓存**：缓存 `(minUserId,maxUserId) -> singleChatId`，降低 `t_single_chat` 读写压力。

## 影响范围
- **模块:** domain(controller/service/mapper), gateway(ws 单聊), common(配置), helloagents(方案包/文档)
- **文件:** 将新增缓存组件与配置，并在现有查询/写入点接入
- **数据:** 新增 Redis key（cache key + TTL），不改变 MySQL schema

## 核心场景

### 需求: 个人信息缓存
**模块:** domain/me

#### 场景: 获取个人资料（读多）
- 首次请求缓存未命中：查 DB（必要时补齐 friendCode）→ 写入 Redis → 返回
- 后续请求缓存命中：直接返回缓存 → 降低 DB 压力

#### 场景: 个人资料变更（写少）
- 变更成功后删除/更新缓存，避免长期脏读

### 需求: 群基本信息缓存
**模块:** domain/group

#### 场景: 获取群资料（读多）
- Redis 仅缓存“群全局字段”（name/avatarUrl/groupCode/createdAt/updatedAt/memberCount）
- `myRole/isMember` 仍需按请求者计算（避免把 A 的权限缓存给 B）

#### 场景: 群资料/成员变更（写少）
- 变更成功后删除/更新“群基本信息缓存”

### 需求: 单聊会话映射缓存
**模块:** gateway/ws + domain/single chat

#### 场景: 单聊发送（高频）
- 先查 Redis `pair -> singleChatId` 命中则直接使用
- 未命中则查 DB；不存在时插入（依赖 MySQL 唯一键 `uk_single_chat_pair` 防重复）
- 将 singleChatId 写入 Redis，后续复用

## 风险评估
- **风险:** 缓存不一致导致短暂展示旧数据
  - **缓解:** cache-aside + 写后删除（或写穿），配置 TTL 做兜底
- **风险:** 把“与请求者相关字段”写入全局缓存导致越权/错显
  - **缓解:** 群 profile 缓存只存全局字段；请求者字段单独计算
- **风险:** Redis 不可用导致功能不可用
  - **缓解:** Redis 失败时降级为 DB 直读（best-effort）

