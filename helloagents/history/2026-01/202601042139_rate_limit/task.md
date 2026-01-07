# 任务清单: Redis 限流（@RateLimit + AOP）

目录: `helloagents/plan/202601042139_rate_limit/`

---

## 1. 限流基础设施
- [√] 1.1 新增 `@RateLimit` 注解与 key 维度枚举（IP/USER/IP_USER）
- [√] 1.2 新增 `RateLimitAspect`：Redis Lua 原子计数与超限判断
- [√] 1.3 新增 `RateLimitExceededException` 并在 `GlobalExceptionHandler` 中映射 429
- [√] 1.4 新增 `RateLimitProperties`：开关、trustForwardedHeaders、failOpen、默认前缀

## 2. 接入关键接口（推荐名单）
- [√] 2.1 给 `src/main/java/com/miniim/auth/web/AuthController.java` 的 `POST /login` 加限流（IP_USER）
- [√] 2.2 给 `src/main/java/com/miniim/domain/controller/FriendRequestController.java` 的 `POST /by-code` 加限流（USER）
- [√] 2.3 给 `src/main/java/com/miniim/domain/controller/GroupController.java` 的创建群/加群等写接口加限流（USER）

## 3. WS 限频（仅文档/建议）
- [√] 3.1 在 `helloagents/wiki/modules/gateway.md` 记录 WS handler 限频建议与阈值建议

## 4. 测试
- [√] 4.1 单元测试：key 生成、IP 解析、脚本返回解析
- [√] 4.2 并发测试（可选）：同 key 并发 INCR 仍保持正确计数与 TTL

## 5. 文档更新
- [√] 5.1 更新 `helloagents/wiki/TESTING_SPEC.md`：如何验证 429 与 retry-after
- [√] 5.2 更新 `helloagents/CHANGELOG.md` 记录限流能力
