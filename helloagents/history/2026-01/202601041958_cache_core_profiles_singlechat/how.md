# 技术设计: Redis 缓存（三项）- 个人信息 / 群基本信息 / 单聊会话映射

## 技术方案

### 核心技术
- Redis（StringRedisTemplate）
- JSON 序列化（ObjectMapper）或 Hash（视对象复杂度决定）
- 缓存模式：Cache-Aside（读：miss→DB→set；写：DB 成功后 del/set）

### Key 设计（建议）
> 统一前缀便于运维排查与清理。

1) 个人信息
- `im:cache:user:profile:{userId}` -> JSON（MeProfileDto 所需字段）
- TTL：10~60 分钟（可配置），写入/更新后主动删除或更新

2) 群基本信息（只缓存全局字段）
- `im:cache:group:base:{groupId}` -> JSON（groupId/name/avatarUrl/groupCode/createdBy/createdAt/updatedAt/memberCount）
- TTL：5~30 分钟（可配置），群资料变更/成员变更后主动删除

3) 单聊会话映射
- `im:cache:single_chat:pair:{minUserId}:{maxUserId}` -> singleChatId（字符串/数字）
- TTL：7 天或更长（可配置），通常稳定；若未来支持删除会话，则删除时清理

### 失效策略（推荐：写后删除）
- 用户资料更新、friendCode reset：删除 `user:profile` 缓存
- 群资料更新（改名/头像/群码 reset）：删除 `group:base` 缓存
- 群成员变更（kick/leave/join/transferOwner/setAdmin）：删除 `group:base` 缓存（memberCount 变化、updatedAt 变化）
- 单聊会话创建：写入 `pair -> id`；若 DB 插入遇唯一冲突则回读 DB 并回填缓存

### 降级策略
- Redis 读/写异常：记录 debug/warn（保持“默认安静”），业务降级为 DB 直读
- 设置合理超时（lettuce 默认即可），避免 Redis 抖动拖垮接口

### 一致性边界（明确可接受）
- 个人信息与群基本信息允许分钟级短暂旧值（由 TTL + 主动失效兜底）
- 单聊映射强一致依赖 MySQL 唯一键；缓存仅作加速

## 测试与验收
- 单元测试：key 生成、序列化/反序列化、Redis 失败降级路径
- 集成验证（本地）：同一接口连续请求命中缓存；更新后缓存失效；并发创建 singleChat 不产生重复会话（唯一键 + 冲突回读）

