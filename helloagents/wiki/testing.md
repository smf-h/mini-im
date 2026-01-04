# 测试说明

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

用于快速验证：`SINGLE_CHAT` 落库 ACK（saved）与接收 ACK（ack_receive）联动是否正常，以及 DB 状态是否推进到 `RECEIVED`。

补充说明：
- 当前离线补发逻辑在服务端 `AUTH` 处理后触发；即使已在握手阶段完成鉴权，仍会在收到 `AUTH` 帧后执行离线补发。
- 可选兜底定时补发默认关闭（`im.cron.resend.enabled=true` 才启用）；频率可通过 `im.cron.resend.fixed-delay-ms` 调整（默认 30s；本地联调可调大减少干扰）。

前置条件：
- 服务端已启动（HTTP `:8080` + WS `:9001/ws`）
- 本机安装 JDK（需要 `javac` / `java`）
- （可选）本机可访问 MySQL（用于校验 `t_message.status`）

运行：
 - 跑全部场景（默认）：`basic + idempotency + offline + cron + auth + friend_request`
  - `powershell -ExecutionPolicy Bypass -File scripts/ws-smoke-test/run.ps1`
- 指定场景：
  - `powershell -ExecutionPolicy Bypass -File scripts/ws-smoke-test/run.ps1 -Scenario basic`
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
- `scenarios.*.dbStatus`：开启 `-CheckDb` 时回填（例如 `5=RECEIVED`、`6=DROPPED`）
- `scenarios.*.dbRow`：开启 `-CheckDb` 时回填的 `t_message` 关键字段快照
- `redis`：开启 `-CheckRedis` 时回填（主要看 `im:gw:route:<userId>` 的 value/ttl）
- `explain`：ACK 与 DB 状态的含义（用于解释“为什么会这样返回”）

敏感信息处理：
- `steps.*.raw` 会对 `token` 字段做脱敏（不会输出 token 明文）。
