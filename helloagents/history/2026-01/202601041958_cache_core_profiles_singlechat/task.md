# 任务清单: Redis 缓存（三项）- 个人信息 / 群基本信息 / 单聊会话映射

目录: `helloagents/plan/202601041958_cache_core_profiles_singlechat/`

---

## 1. 缓存基础设施
- [√] 1.1 新增缓存组件（如 `RedisJsonCache`）：封装 get/set/del、TTL、降级日志策略
- [√] 1.2 新增配置项（TTL/启用开关），并在 `application*.yml` 提供默认值

## 2. 个人信息缓存（/me/profile）
- [√] 2.1 在 `src/main/java/com/miniim/domain/controller/MeController.java` 的 profile 查询接入缓存（cache-aside）
- [√] 2.2 在 friendCode reset/个人资料更新（如有接口）后执行缓存失效

## 3. 群基本信息缓存（/group/profile/*）
- [√] 3.1 在 `GroupManagementServiceImpl` 中拆分“群全局字段获取”与“请求者字段计算”
- [√] 3.2 为“群全局字段获取”接入缓存（只缓存 base 字段）
- [√] 3.3 在群资料/成员变更成功后统一触发 `group:base` 缓存失效

## 4. 单聊会话映射缓存（pair->singleChatId）
- [√] 4.1 在 `src/main/java/com/miniim/domain/service/impl/SingleChatServiceImpl.java` 的 find/getOrCreate 接入 Redis 缓存
- [√] 4.2 处理并发插入冲突：捕获唯一键冲突后回读 DB 并回填缓存

## 5. 安全检查
- [√] 5.1 检查缓存字段：不缓存敏感数据（密码/refreshToken 明文等）
- [√] 5.2 检查群 profile：不缓存 `myRole/isMember` 这类与请求者相关字段

## 6. 文档更新
- [√] 6.1 更新 `helloagents/wiki/modules/gateway.md` 或新增缓存模块文档：key/TTL/失效点
- [√] 6.2 更新 `helloagents/CHANGELOG.md` 记录缓存优化

## 7. 测试
- [√] 7.1 增加单元测试覆盖：缓存命中/失效/Redis 不可用降级
- [√] 7.2 增加并发测试（或最小模拟）：singleChat 并发创建不产生重复并最终缓存一致
