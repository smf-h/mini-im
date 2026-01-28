# 测试说明

> 如果你用 `java -jar` 启动后端且本机 MySQL 账号有密码，请先设置环境变量：`$env:IM_MYSQL_PASSWORD="<your_password>"`（或用启动参数覆盖 `spring.datasource.*`）。

## Windows 本地 Redis（无安装权限时的可复现方案）

本项目的多实例路由、幂等与部分压测脚本依赖 Redis（含 Streams）。若本机没有可用 Redis，可用 Memurai（Redis 7.2 兼容）以“免安装提取”的方式启动：

- 下载 MSI（如可联网）：`Invoke-WebRequest -Uri "https://dist.memurai.com/releases/Memurai-Developer/4.1.2/Memurai-Developer-v4.1.2.msi" -OutFile "$env:TEMP\\Memurai-Developer-v4.1.2.msi"`
- 解包 MSI（不执行安装器自定义动作）：`Start-Process msiexec.exe -ArgumentList "/a `"$env:TEMP\\Memurai-Developer-v4.1.2.msi`" /qn TARGETDIR=`"C:\\Temp\\memurai_portable`"" -Wait`
- 启动 Redis：`Start-Process -FilePath "C:\\Temp\\memurai_portable\\Memurai\\memurai.exe" -ArgumentList @("--port","6379","--bind","127.0.0.1","--protected-mode","no","--appendonly","no") -WindowStyle Hidden`
- 验证：`& "C:\\Temp\\memurai_portable\\Memurai\\memurai-cli.exe" -p 6379 ping`（期望 `PONG`）

## k6 分布式压测（WS）

仓库已提供可直接运行的 k6 脚本与 PowerShell 包装：

- `scripts/k6/ws_ping.js`：WS 连接 + AUTH + PING/PONG RTT（即时性基线）
- `scripts/k6/ws_single_e2e.js`：单聊 E2E（偶数 VU 发、奇数 VU 收），含重复/乱序统计
- `scripts/k6/run-ws-ping.ps1`、`scripts/k6/run-ws-single-e2e.ps1`：Windows 一键运行

分布式执行与参数说明见：`scripts/k6/README.md`

## 备用：无外网 GitHub 的压测（Java 脚本）

在无法从 `github.com` 下载 k6 的网络环境下，可使用仓库自带 Java 压测脚本（不依赖第三方二进制）：

- `scripts/ws-load-test/run.ps1`：支持 `connect`/`ping`/`single_e2e` 三种模式，可做连接压测、PING/PONG RTT、单聊 E2E（含重复/乱序统计）
  - 可选：`-FlapIntervalMs/-FlapPct` 模拟网络抖动（断连重连）
  - 可选：`-SlowConsumerPct/-SlowConsumerDelayMs` 模拟慢消费者（延迟读取）
  - 可选：`-NoReadPct` 模拟“接收端不读”（用于触发服务端 `channel.isWritable()` 翻转）
  - 可选：`-BodyBytes` 控制消息体大小（建议 `4000`，贴近服务端 `MAX_BODY_LEN=4096`，用于更快触发背压）
  - 可选：`-SendModel spread|burst` 控制“发送节奏”
    - `spread`：把发送均匀摊到整个 `msgIntervalMs` 窗口内（更接近真实随机到达）
    - `burst`：所有 sender 在同一 tick 发送（每 `msgIntervalMs` 产生一次“微突发”，用于 worst-case 验证排队/限流）
  - 可选：`-JavaExe/-JavacExe` 指定 JDK 路径（Windows 下避免 `java` 指向 `Oracle javapath` 造成不可控）
  - 输出说明：压测输出 JSON 会包含 `userBase/msgIntervalMs/bodyBytes/slowConsumerPct/noReadPct` 等关键参数快照，便于你复现同口径
  - 输出说明：`single_e2e` 会输出 `e2eMsFast/e2eMsSlow`（拆分普通接收端与慢接收端的延迟分位数），避免“汇总 P99 被慢端拖高”导致误判

### 5 实例一键分级压测（单机/Windows）

用于：一键启动 5 个网关实例（共用同一 MySQL/Redis），并按分级（默认 `500/5000/50000`）执行 connect/ping/single_e2e + 群聊 push 压测。

- `powershell -ExecutionPolicy Bypass -File scripts/ws-cluster-5x-test/run.ps1`
  - ⚠️ 若你的 PowerShell Profile 启用了 conda auto activate，且出现 `UnicodeEncodeError: 'gbk' codec can't encode ...`，建议在**已打开的 PowerShell 会话**里用 `& "scripts/ws-cluster-5x-test/run.ps1" ...` 直接调用（避免启动子 powershell 进程时触发 conda hook）。
  - 默认不会跑 `500000`（单机高概率会把本机压测端先打挂）；如确实要尝试，可加 `-Run500k`（风险自担）
  - 默认会从 **User-level 环境变量** 补齐 `IM_MYSQL_PASSWORD`（避免“每次开新 PowerShell 进程变量丢失”）
  - 输出目录：`logs/ws-cluster-5x-test_YYYYMMDD_HHMMSS/`（内含每一步 JSON 与每个实例日志）
  - 单机多实例建议保持 `AutoTuneLocalThreads=true`（默认开启）：会按实例数自动对齐 Netty worker 线程、DB executor、JDBC 连接池等，避免“实例数上去→线程数爆炸→DB 排队超时→ERROR internal_error”
  - 口径对齐：默认 `LoadSendModel=spread`（均匀摊平发送）；如需验证“齐发微突发”的 worst-case，可指定 `-LoadSendModel burst`（见 `helloagents/wiki/test_run_20260116_ablation_sendmodel_vs_autotune.md:1`）
  - 口径对齐：可用 `-LoadDrainMs 5000`（或更大）在停止发送后保留连接一段时间，确保 `orTimeout(3s)` 触发的 `ERROR` 不会被“提前关连接”掩盖
  - 快速回归：可用 `-SkipGroup` 跳过群聊压测（只测 connect/ping/single_e2e），用于做实例数 sweep（例如 5~9）时节省时间
  - Redis down 专测时可用：`-SkipSmoke/-SkipSingleE2e`（只测 connect/ping 或手动指定单实例功能测试）
  - Redis 覆盖（用于“Redis down/抖动”专测）：
    - `-RedisHost/-RedisPort/-RedisDatabase`：把网关实例指向指定 Redis
    - `-SkipRedisCheck`：跳过 Redis 端口连通性检查（例如把 `-RedisPort 1` 用作“模拟 Redis 不可用”）
      - 说明：当使用 `-SkipRedisCheck` 时，`scripts/ws-cluster-5x-test/run.ps1` 会默认跳过 cluster smoke（除非显式指定 `-SkipSmoke:$false`），避免在 Redis down 场景下产生“必然失败”的误报。
    - `-RedisConnectTimeoutMs/-RedisTimeoutMs`：控制 Redis 连接/命令超时（默认 500ms），用于减少 Redis 不可用时的“阻塞型抖动”

### 群聊 push 压测（含乱序/重复统计）

用于：通过 HTTP 自动注册/登录用户、创建群并拉人，然后建立 WS 连接发送 `GROUP_CHAT`，统计：吞吐、E2E 分位数、重复率与乱序。

- `powershell -ExecutionPolicy Bypass -File scripts/ws-group-load-test/run.ps1 -WsUrls "ws://127.0.0.1:9001/ws;ws://127.0.0.1:9002/ws" -HttpBase "http://127.0.0.1:8080" -Clients 200 -Senders 20 -DurationSeconds 60 -MsgIntervalMs 50 -ReceiverSamplePct 30`

乱序口径说明：
- `reorderByFrom`：同一发送者（from）维度的乱序/重复触发（更符合“会话内顺序”验证）
- `reorderByServerMsgId`：接收端看到的全局 `serverMsgId` 非单调（跨发送者/跨实例 push 并发下通常**不保证**）

### ws_perf 分段耗时解析（服务端）

本项目不再内置 `ws_perf` 分段日志解析链路；性能排查建议优先结合压测脚本的端到端统计与应用日志逐步定位瓶颈（DB/Redis/网关背压等）。

## WS 背压/慢消费者保护（服务端）

为避免慢消费者导致 Netty 出站缓冲持续堆积（内存上涨 + 全局延迟抖动），服务端新增背压配置：

- `im.gateway.ws.backpressure.enabled`：默认启用
- `im.gateway.ws.backpressure.write-buffer-low-water-mark-bytes`：默认 `262144`（256KB）
- `im.gateway.ws.backpressure.write-buffer-high-water-mark-bytes`：默认 `524288`（512KB）
- `im.gateway.ws.backpressure.close-unwritable-after-ms`：默认 `3000`（连接持续 unwritable 超过该阈值会被踢下线；设置 `<0` 可禁用踢人）
- `im.gateway.ws.backpressure.drop-when-unwritable`：默认启用（当连接已 unwritable 时拒绝继续写入，避免缓冲无限增长）

可观测事件：
- 日志关键字：`ws backpressure: closing slow consumer channel`

回归用例（慢消费者）：
- `powershell -ExecutionPolicy Bypass -File scripts/ws-load-test/run.ps1 -Mode single_e2e -WsUrls ws://127.0.0.1:9001/ws -Clients 200 -DurationSeconds 60 -MsgIntervalMs 100 -SlowConsumerPct 30 -SlowConsumerDelayMs 5000`

多实例回归（含背压演练，一键启动 2 实例 + smoke + 3 组负载）：
- `powershell -ExecutionPolicy Bypass -File scripts/ws-backpressure-multi-test/run.ps1 -JavaExe "<path-to-java.exe>" -Clients 200 -DurationSeconds 20 -MsgIntervalMs 20 -BodyBytes 4000 -SlowConsumerPct 30 -SlowConsumerDelayMs 5000 -NoReadPct 30 -BpLowBytes 32768 -BpHighBytes 65536 -BpCloseAfterMs 1500 -TimeoutMs 60000`

说明：`scripts/ws-backpressure-multi-test/run.ps1` 会在 `logs/bp-multi/` 输出 `meta_*.json`（含真实 JVM PID/端口/日志路径）与 `mem_*.csv`（含 pid 列），用于定位慢消费者导致的内存上涨与尾延迟放大。

## 单元测试（快速回归）

- `mvn test`


> **注意**：详细的测试规范、分层策略及代码编写要求，请参考 [TESTING_SPEC.md](TESTING_SPEC.md)。本文档主要侧重于“如何运行”现有的联调与冒烟脚本。

## 前端联调（Vue3 + TypeScript）

前置条件：
- 服务端已启动（HTTP `:8080` + WS `:9001/ws`）
- 本机安装 Node.js（包含 `npm`）

运行：
- `cd frontend`
- `npm install`
- `npm run dev`

说明：
- 浏览器端 WS 连接会使用 `?token=<accessToken>` 进行握手鉴权，连接建立后再发送 `AUTH` 帧。
- 默认后端地址：若未设置 `VITE_HTTP_BASE/VITE_WS_URL`，前端会使用“页面 hostname + 固定端口(8080/9001)”进行拼装，避免在非本机访问时误用 `127.0.0.1`。
- 数字精度：服务端对超出 JS 安全整数范围（`2^53-1`）的 `long/Long` 字段会输出为字符串，联调时如看到 `"id":"2004..."` 属正常现象。

## WebSocket 单聊冒烟（核心不依赖 Redis）

用于快速验证：`SINGLE_CHAT` 落库 ACK（saved）与接收端 `ACK(delivered/read)` 推进成员游标是否正常（兼容 `ack_read`）。

补充说明：
- 当前离线补发逻辑在服务端 `AUTH` 处理后触发；即使已在握手阶段完成鉴权，仍会在收到 `AUTH` 帧后执行离线补发。
- 可选兜底定时补发默认关闭（`im.cron.resend.enabled=true` 才启用）；频率可通过 `im.cron.resend.fixed-delay-ms` 调整（默认 30s；本地联调可调大减少干扰）。

前置条件：
- 服务端已启动（HTTP `:8080` + WS `:9001/ws`）
- 本机安装 JDK（需要 `javac` / `java`）
- （可选）本机可访问 MySQL（用于校验消息是否落库/撤回等 `t_message.status`；送达/已读最终态以成员游标为准）

运行：
 - 跑全部场景（默认）：`basic + idempotency + offline + cron + auth + friend_request`
  - `powershell -ExecutionPolicy Bypass -File scripts/ws-smoke-test/run.ps1`
- 指定场景：
  - `powershell -ExecutionPolicy Bypass -File scripts/ws-smoke-test/run.ps1 -Scenario basic`

可选检查依赖说明：
- `-CheckRedis`：需要本机可用的 `redis-cli`；若未安装会在输出 JSON 中标记 `redis.skipped=true`（不影响主流程冒烟）。
- `-CheckDb`：需要本机 `mysql` CLI 且提供 `-DbPassword`（或 `MYSQL_PWD`）。
  - `powershell -ExecutionPolicy Bypass -File scripts/ws-smoke-test/run.ps1 -Scenario idempotency`
  - `powershell -ExecutionPolicy Bypass -File scripts/ws-smoke-test/run.ps1 -Scenario offline`
  - `powershell -ExecutionPolicy Bypass -File scripts/ws-smoke-test/run.ps1 -Scenario cron`
  - `powershell -ExecutionPolicy Bypass -File scripts/ws-smoke-test/run.ps1 -Scenario friend_request`
- 鉴权全链路（握手鉴权 + AUTH 帧 + token 过期断连）：
  - `powershell -ExecutionPolicy Bypass -File scripts/ws-smoke-test/run.ps1 -Scenario auth -AuthTokenTtlSeconds 2`
  - 说明：auth 场景会覆盖 `REAUTH`（续期）——旧 token 过期后先 REAUTH，再验证 PING 仍可用，直到新 token 过期
- 注意：如果你刚改了服务端代码（新增 `REAUTH`），务必重启服务端进程后再跑脚本，否则会因为服务端尚未加载新逻辑而失败
- 同时校验 DB 状态（示例使用 root 账号）：
  - `powershell -ExecutionPolicy Bypass -File scripts/ws-smoke-test/run.ps1 -CheckDb -DbPassword "<your_password>"`
  - DB 校验会把每个场景对应 `serverMsgId` 的 `t_message` 记录（含 `status/updatedAt/from/to/...`）回填到输出 JSON 中
- （可选）打印 Redis 路由 key（需要本机有 `redis-cli`）：
  - `powershell -ExecutionPolicy Bypass -File scripts/ws-smoke-test/run.ps1 -CheckRedis`

可调参数：
- `-WsUrl`：默认 `ws://127.0.0.1:9001/ws`
- `-HttpBase`：默认 `http://127.0.0.1:8080`（用于 friend_request 场景的 HTTP 校验）
- `-JwtSecret`：默认使用 `application.yml` 的默认值（可覆盖）
- `-UserA/-UserB`：默认 `10001/10002`
- `-TimeoutMs`：默认 `8000`
- `-Scenario`：默认 `all`
- `-AuthTokenTtlSeconds`：鉴权场景专用（用于验证 `token_expired`）
- `-RedisHost/-RedisPort/-RedisPassword`：Redis 查询专用（密码不会输出；但传参会出现在本机进程参数里，介意的话建议用无密码本地 Redis）

输出字段说明：
- `ok`：整体是否通过（任一场景失败则为 false）
- `vars`：关键变量快照（ws/scenario/userA/userB/timeoutMs/authTokenTtlSeconds/redisHost/redisPort）
- `scenarios`：每个场景的结果（包含 `clientMsgId/serverMsgId/expected` 等）
- `scenarios.*.steps`：每一步的消息内容与原因（包含 `raw` 原始 JSON，便于你对照客户端协议）
- `scenarios.*.dbStatus`：开启 `-CheckDb` 时回填（例如 `t_message.status=1(SAVED)/4(REVOKED)`；送达/已读不看该字段）
- `scenarios.*.dbRow`：开启 `-CheckDb` 时回填的 `t_message` 关键字段快照
- `redis`：开启 `-CheckRedis` 时回填（主要看 `im:gw:route:<userId>` 的 value/ttl）
- `explain`：ACK 与 DB 状态的含义（用于解释“为什么会这样返回”）

敏感信息处理：
- `steps.*.raw` 会对 `token` 字段做脱敏（不会输出 token 明文）。

## WebSocket 多实例/多端冒烟（两实例联调）

用于快速验证：
- 两实例路由：跨实例 PUSH（SINGLE_CHAT、GROUP_CHAT/GROUP_NOTIFY）
- 发送者顺序：同一连接连续发送两条消息，对端到达顺序不乱
- 群聊策略：`auto/push/notify/none`（notify 会走 HTTP `/group/message/since` 拉取增量）
- 单登录踢下线：同一用户在不同实例建立新连接后，旧连接会收到 `ERROR {reason:kicked}` 并断开（可用 `-DmultiDevice=false` 关闭该断言）
  - `run.ps1/auto-run.ps1` 可直接用 `-NoMultiDevice` 关闭

前置条件：
- 两个后端实例已启动（HTTP/WS 端口不同；且共用同一 Redis/MySQL）
- 本机安装 JDK（`javac`/`java`）

运行：
- `powershell -ExecutionPolicy Bypass -File scripts/ws-cluster-smoke-test/run.ps1`
- 一键启动两实例并执行（推荐）：`powershell -ExecutionPolicy Bypass -File scripts/ws-cluster-smoke-test/auto-run.ps1`
  - 默认会检查本机 `MySQL:3306` 与 `Redis:6379` 是否可连；若你暂时没启动依赖，可加 `-SkipInfraCheck`
  - 默认会在缺少 jar 时自动 `mvn -DskipTests package`；若你已手动打包，可加 `-SkipBuild`
  - 默认会额外跑一次“朋友圈（Moments）HTTP 冒烟”；如只想测 WS，可加 `-SkipMomentsSmoke`
  - 失败排查：实例日志会写到 `logs/ws-cluster-smoke/`

常用参数：
- `-WsUrlA/-WsUrlB`：默认 `ws://127.0.0.1:9001/ws` / `ws://127.0.0.1:9002/ws`
- `-HttpBaseA/-HttpBaseB`：默认 `http://127.0.0.1:8080` / `http://127.0.0.1:8082`
- `-GroupStrategyMode`：`auto|push|notify|none`（用于断言服务端实际下发类型；若要测 notify，请用启动参数把服务端 `im.group-chat.strategy.mode` 设为 `notify`）
- `-UserAName/-UserBName/-Password`：脚本会走 `/auth/login` 自动注册并拿 token；不会输出 token 明文

## 朋友圈（Moments）HTTP 冒烟

用于快速验证：好友可见性（好友+自见）、发动态、时间线拉取、点赞 toggle、评论增删、删除动态。

前置条件：
- 服务端已启动（至少一个 HTTP 实例）

运行：
- 单独跑：`powershell -ExecutionPolicy Bypass -File scripts/moments-smoke-test/run.ps1 -HttpBaseA http://127.0.0.1:8080 -HttpBaseB http://127.0.0.1:8082 -Password p`
- 跟随多实例自动冒烟：默认 `scripts/ws-cluster-smoke-test/auto-run.ps1` 会一并执行（可用 `-SkipMomentsSmoke` 跳过）
