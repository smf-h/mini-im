# 模块: cache

## 职责
- 提供 Redis 缓存能力（读优化 + 降 DB 压力）
- 约束：MySQL 仍为 SSOT；Redis 仅做 cache-aside 与加速

## 关键实现（以代码为准）
- JSON 缓存工具：`com.miniim.common.cache.RedisJsonCache`
- 缓存配置：`com.miniim.common.cache.CacheProperties`（`im.cache.*`）
- 用户资料缓存：`com.miniim.domain.cache.UserProfileCache`（key: `im:cache:user:profile:{userId}`）
- 好友ID集合缓存：`com.miniim.domain.cache.FriendIdsCache`（key: `im:cache:friend:ids:{userId}` -> `{"ids":[...]} `，支持缓存空集合）
- 群基本信息缓存：`com.miniim.domain.cache.GroupBaseCache`（key: `im:cache:group:base:{groupId}`，不缓存 `myRole/isMember`）
- 群成员ID集合缓存：`com.miniim.domain.cache.GroupMemberIdsCache`（Redis Set；key: `im:cache:group:member_ids:{groupId}` -> `userId`）
- 单聊会话映射缓存：`com.miniim.domain.cache.SingleChatPairCache`（key: `im:cache:single_chat:pair:{minUid}:{maxUid}` -> `singleChatId`）

## 配置与约定
- `im.cache.user-profile-ttl-seconds`：个人信息 TTL（默认 1800s）
- `im.cache.friend-ids-ttl-seconds`：好友ID集合 TTL（默认 1800s；写路径“主动失效”保障一致性）
- `im.cache.group-base-ttl-seconds`：群基本信息 TTL（默认 600s）
- `im.cache.group-member-ids-ttl-seconds`：群成员ID集合 TTL（默认 600s；同时依赖“成员变更主动失效”兜底一致性）
- `im.cache.single-chat-pair-ttl-seconds`：单聊映射 TTL（默认 604800s）

## 失效点（写后删除）
- FriendCode 重置后：删除 `user:profile` 缓存
- 好友关系变更（同意/解除）后：删除双方 `friend:ids` 缓存（避免朋友圈/可见性等读到脏数据）
- 群成员/群资料变更后：删除 `group:base` 缓存
- 群成员变更（入群/退群/踢人）后：删除 `group:member_ids` 缓存（群发/群成员列表会回填）
