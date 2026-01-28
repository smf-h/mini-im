# 变更提案: WS 单端登录踢下线 + 多实例路由

## 需求背景
当前 WS 网关的 Session/Channel 只存在于单个实例的内存中：一旦部署为多实例（多进程/多容器），将出现以下问题：
1. 无法可靠“踢下线”：新连接在实例 A，旧连接在实例 B，A 无法直接关闭 B 的 Channel。
2. 推送/实时消息可能落错实例：消息写到 DB 后，推送只在本机查 Channel，跨实例无法投递。
3. 重连风暴下资源压力放大：大量重连会触发鉴权与离线补发，DB/Redis/QPS 受冲击。
4. 幂等只做了本地缓存：多实例/重连后，(userId + clientMsgId) 的重复请求可能在不同实例重复落库。
5. 双 token（access+refresh）下“立即下线”存在天然限制：无状态 accessToken 在过期前仍可用，需要服务端状态协助才能做到真正的即时失效。

## 变更内容
1. 引入“多实例会话路由 SSOT”：Redis 存 `userId -> {serverId, connId}`，并通过 TTL 表示在线。
2. 实现“单端登录（按 userId）”：新连接成功鉴权后，踢掉旧连接（同机与跨机）。
3. 引入实例间控制通道：Redis Pub/Sub 将 `KICK/PUSH` 指令路由到目标 `serverId`，由目标实例操作本机 Channel。
4. 幂等升级：客户端幂等 key 从本地缓存升级为 Redis SETNX（可降级），保证跨实例/重连下也不重复落库。
5. 重连风暴缓解：对“离线补发/鉴权”引入分布式去重锁与限频，避免瞬时打爆 DB。

## 影响范围
- **模块:** gateway(ws/session), auth(jwt/登录), common(配置/文档), helloagents(方案包/知识库)
- **文件:** 预计涉及 `src/main/java/com/miniim/gateway/**` 与 `src/main/java/com/miniim/auth/**` 多文件改动
- **API:** token 的 claim 校验策略可能调整（如引入 sessionVersion）
- **数据:** 新增 Redis key（路由/幂等/锁）

## 核心场景

### 需求: 单端登录踢下线（按 userId）
**模块:** gateway/ws + gateway/session
同一 userId 同时只能存在 1 条“有效 WS 会话”。

#### 场景: 新连接成功鉴权
客户端在实例 A 新建连接并鉴权成功：
- Redis 记录 `userId -> {A, connIdNew}` 并返回旧路由 `{B, connIdOld}`
- 向 `B` 发布 `KICK(userId, connIdOld)`；B 收到后只关闭匹配 connId 的 Channel
- A 本机如果仍有旧 Channel，则本机关闭旧 Channel（只保留新 Channel）

### 需求: 多实例推送正确落点
**模块:** gateway/ws
服务端需要对指定 userId 推送消息时，必须能找到“该 userId 在线的实例”并完成投递。

#### 场景: 推送给在线用户
- 若 userId 的路由 serverId 为本机：直接写本机 Channel
- 若为其他实例：发布 `PUSH(serverId, envelope)`，由目标实例落到本机 Channel

### 需求: 重连风暴下的稳定性
**模块:** gateway/ws + gateway/session

#### 场景: 客户端网络抖动连续重连
- 仅允许同一 userId 在短窗口内触发一次离线补发（跨实例）
- 鉴权/补发抢不到锁时返回可重试错误或直接断开，避免 DB 风暴

### 需求: 幂等跨实例可靠
**模块:** gateway/ws

#### 场景: 客户端重复发送同一个 clientMsgId（重试/重连）
- 多实例场景下依旧只允许一次“落库/业务执行”
- 后续重复请求直接返回 ACK（或等价结果）

## 风险评估
- **风险:** 新增 Redis Pub/Sub 与路由 key，若 Redis 不可用会影响多实例能力
  - **缓解:** 保持降级路径：Redis 不可用时仅保证单实例本机可用，并把相关行为降级为 best-effort
- **风险:** “立即下线”需要服务端状态（sessionVersion/jti）会改变 token 校验路径
  - **缓解:** 通过配置开关控制是否启用；默认仅做踢连接（不强制 token 立即失效）

