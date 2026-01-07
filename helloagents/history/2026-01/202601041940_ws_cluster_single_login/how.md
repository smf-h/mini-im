# 技术设计: WS 单端登录踢下线 + 多实例路由

## 技术方案

### 核心技术
- Spring Boot + Netty WebSocket
- Redis（StringRedisTemplate）：
  - KV：会话路由 SSOT、幂等 key、分布式锁
  - Pub/Sub：跨实例 KICK/PUSH 控制通道
- JWT（JJWT）：accessToken 解析；可选引入 sessionVersion 进行“即时失效”

### 实现要点

#### 1) serverId 与 connId
- `serverId`：每个网关实例的唯一标识，优先配置 `im.gateway.instance-id`；未配置时使用 `hostname + wsPort` 组合并追加随机后缀，避免多实例冲突。
- `connId`：每条 WS 连接生成的连接标识，建议使用 `UUID` 并放入 `Channel.attr`；用于路由比对与防误踢。

#### 2) Redis 路由 SSOT（userId -> serverId+connId）
Key 设计（建议）：
- `im:gw:route:{userId}` -> `{serverId}|{connId}`（字符串）
- TTL：与心跳/idle 相关，维持在线状态（例如 120s）

关键原子操作（Lua 脚本）：
1. `SET_AND_GET_OLD`：设置新路由（带 TTL），同时返回旧路由值（用于踢旧连接）
2. `EXPIRE_IF_MATCH`：仅当当前值匹配本连接 `{serverId}|{connId}` 时才续期，避免旧连接“抢回路由”
3. `DEL_IF_MATCH`：仅当当前值匹配本连接时才删除，避免误删新连接路由

#### 3) 跨实例踢下线（KICK）
控制通道（建议）：
- Pub/Sub topic：`im:gw:ctrl:{serverId}`
- 消息体（JSON）：`{type:"KICK", userId, connId?, reason, ts}`

处理逻辑：
- 新连接鉴权成功后：
  - 调用 `SET_AND_GET_OLD` 写入新路由并获得 `oldRoute`
  - 若 `oldRoute.serverId != currentServerId`：向 `oldRoute.serverId` 发布 `KICK`
  - 若 `oldRoute.serverId == currentServerId`：在本机关闭旧 Channel（按 connId 精确匹配）
- 目标实例收到 `KICK`：
  - 只关闭 `userId` 对应的 Channel（若有 connId 仅关闭匹配；否则关闭全部）
  - 关闭前可选下发错误包（reason=`kicked`）

#### 4) 多实例推送（PUSH）
推送通道与 KICK 复用同一个 `ctrl:{serverId}` topic：
- 消息体（JSON）：`{type:"PUSH", userId, envelope, ts}`

推送入口（服务端）：
- 若本机存在活跃 Channel：直接写
- 否则查 Redis route：
  - route 指向本机：仍尝试写（可能是短暂竞态）
  - route 指向其他实例：向其发布 `PUSH`，由目标实例执行本地写

> 说明：PUSH 消息体包含 envelope JSON。后续如担心体积/带宽，可升级为“仅传 serverMsgId，目标实例落库拉取”。

#### 5) 重连风暴缓解（分布式去重锁/限频）
建议新增锁：
- `im:gw:lock:resend:{userId}`：TTL 5~10s；抢到锁才触发离线补发
- `im:gw:lock:auth:{userId}`：TTL 1~3s；可选，用于限制极端 AUTH 风暴

策略：
- 抢不到 `resend` 锁：跳过补发（不报错），等待下一次窗口
- 抢不到 `auth` 锁：返回 `busy_retry` 并断开（或仅返回错误不关）

#### 6) 双 token 的“立即下线”可选方案（sessionVersion）
问题：无状态 accessToken 在 1800s 内仍然有效，踢连接后旧客户端仍能用旧 token 重连并“反踢”新连接。

可选解决：
- Redis 存 `im:auth:sv:{userId}`（整数，登录时 `INCR`）
- accessToken 增加 claim `sv`
- WS/HTTP 校验 accessToken 时，同时校验 `sv == redis.sv(userId)`；不一致则拒绝

兼容性与降级：
- 可通过配置开关启用
- Redis 不可用时可降级为“只校验 JWT 自身”，保证可用性

## 安全与性能
- **安全:** KICK/PUSH 只走服务端 Redis 通道，不暴露给客户端；connId 比对避免误踢
- **性能:** 路由续期采用 `EXPIRE_IF_MATCH`，避免频繁 `SET`；补发加锁降低 DB 压力

## 测试与部署
- **本地多实例测试:** 启动 2 个实例（不同 `serverId/wsPort`），用两个 WS 客户端验证：
  - 新连接鉴权后旧连接被踢
  - A 推送到 userId 能落到 B 的 Channel
  - 旧连接无法续期抢回 route（TTL 不应被旧连接刷新）
- **自动化测试建议:**
  - 单元测试：Lua 脚本参数/返回解析、RouteInfo 解析、KICK 选择关闭策略
  - 集成测试（可选）：Testcontainers Redis，启动 Spring 上下文验证 Pub/Sub 收发

