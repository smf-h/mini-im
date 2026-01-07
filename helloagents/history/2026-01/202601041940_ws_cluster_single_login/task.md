# 任务清单: WS 单端登录踢下线 + 多实例路由

目录: `helloagents/plan/202601041940_ws_cluster_single_login/`

---

## 1. 路由与会话（Redis SSOT + connId）
- [√] 1.1 在 `src/main/java/com/miniim/gateway/session/SessionRegistry.java` 增加 `connId` 与路由值 `{serverId}|{connId}`，并将 touch/unbind 改为“仅在匹配时续期/删除”，验证 why.md#需求-单端登录踢下线按-userid-场景-新连接成功鉴权
- [√] 1.2 新增 `src/main/java/com/miniim/gateway/session/WsRouteStore.java`（或同等组件）封装 Lua 脚本：`SET_AND_GET_OLD`/`EXPIRE_IF_MATCH`/`DEL_IF_MATCH`，验证 why.md#需求-重连风暴下的稳定性-场景-客户端网络抖动连续重连

## 2. 跨实例控制通道（KICK/PUSH）
- [√] 2.1 新增 `src/main/java/com/miniim/gateway/ws/cluster/WsClusterBus.java`：定义消息模型并提供 publish API，验证 why.md#需求-单端登录踢下线按-userid-场景-新连接成功鉴权
- [√] 2.2 新增 `src/main/java/com/miniim/gateway/ws/cluster/WsClusterListener.java` + Spring 配置订阅本机 topic，收到 `KICK/PUSH` 后在本机执行 close/写入，验证 why.md#需求-多实例推送正确落点-场景-推送给在线用户
- [√] 2.3 修改 `src/main/java/com/miniim/gateway/ws/WsPushService.java`：引入“本机优先，否则按路由 publish”的推送逻辑，验证 why.md#需求-多实例推送正确落点-场景-推送给在线用户

## 3. 单端登录策略（按 userId）
- [√] 3.1 修改 `src/main/java/com/miniim/gateway/ws/WsAuthHandler.java` 与 `src/main/java/com/miniim/gateway/ws/WsHandshakeAuthHandler.java`：鉴权成功后触发“踢旧连接”，验证 why.md#需求-单端登录踢下线按-userid-场景-新连接成功鉴权

## 4. 幂等升级（跨实例）
- [√] 4.1 修改 `src/main/java/com/miniim/gateway/ws/ClientMsgIdIdempotency.java`：增加 Redis SETNX 方案并保留 Caffeine 降级，验证 why.md#需求-幂等跨实例可靠-场景-客户端重复发送同一个-clientmsgid重试重连

## 5. 重连风暴缓解
- [√] 5.1 修改 `src/main/java/com/miniim/gateway/ws/WsResendService.java`：增加 `resend_lock`（跨实例）避免重复补发，验证 why.md#需求-重连风暴下的稳定性-场景-客户端网络抖动连续重连

## 6. 可选：即时失效（sessionVersion）
- [√] 6.1 新增 `src/main/java/com/miniim/auth/service/SessionVersionStore.java` 并改造 `src/main/java/com/miniim/auth/service/JwtService.java`/`src/main/java/com/miniim/auth/service/AuthService.java`：签发 accessToken 时带 `sv` claim，验证 why.md#需求-单端登录踢下线按-userid-场景-新连接成功鉴权
- [√] 6.2 修改 `src/main/java/com/miniim/auth/web/AccessTokenInterceptor.java` 与 `src/main/java/com/miniim/gateway/ws/WsAuthHandler.java`/`src/main/java/com/miniim/gateway/ws/WsHandshakeAuthHandler.java`：校验 `sv`，不一致拒绝，验证 why.md#需求-重连风暴下的稳定性-场景-客户端网络抖动连续重连

## 7. 安全检查
- [√] 7.1 执行安全检查（按G9: 输入验证、敏感信息处理、权限控制、Redis不可用降级策略、避免误踢/误删路由）

## 8. 文档更新
- [√] 8.1 更新 `helloagents/wiki/modules/gateway.md`：补充多实例路由/KICK/PUSH/幂等与测试说明
- [√] 8.2 更新 `helloagents/CHANGELOG.md`：记录本次多实例能力增强

## 9. 测试
- [√] 9.1 新增最小单元测试（如 `src/test/java/...`）：覆盖 RouteInfo 解析、Lua 返回处理与“仅匹配才续期/删除”逻辑
- [√] 9.2 补充本地多实例手工测试说明（`helloagents/wiki/TESTING_SPEC.md` 或 `scripts/` 下说明）
