# 模块: config

## 职责
- 集中管理基础配置（MyBatis-Plus、线程池等）
- 管理应用级配置约定（如数据库迁移策略）

## 关键实现（以代码为准）
- MyBatis-Plus 配置：`com.miniim.config.MybatisPlusConfig`
- 自动填充：`com.miniim.config.MyMetaObjectHandler`
- 线程池：`com.miniim.config.ImExecutorsConfig`
- 数据库迁移（Flyway）：`src/main/resources/db/migration/*` + `spring.flyway.*`（见 `src/main/resources/application.yml`）
  - 启动自愈：`im.flyway.auto-repair`（默认 true；设置为 false 可关闭）。当 `flyway.validate` 失败时自动 `repair()` 并重试迁移，实现见 `com.miniim.config.FlywayAutoRepairConfig`
- 码生成配置（FriendCode/GroupCode）：`im.code.*`（见 `src/main/resources/application.yml`、`src/main/java/com/miniim/domain/config/CodeProperties.java`）
- 缓存配置：`im.cache.*`（见 `src/main/resources/application.yml`、`com.miniim.common.cache.CacheProperties`）
- 限流配置：`im.ratelimit.*`（见 `src/main/resources/application.yml`、`com.miniim.common.ratelimit.RateLimitProperties`）
- 网关实例标识：`im.gateway.ws.instance-id`（可选；缺省为 `{host}:{port}:{random8}`）

## 配置模板（推荐）
- 变量模板：`src/main/resources/application.env.yml`（仅参数变量，便于容器/CI 注入）
- 示例值：`src/main/resources/application.env.values.yml`（与变量模板一一对应；不包含真实敏感信息）
