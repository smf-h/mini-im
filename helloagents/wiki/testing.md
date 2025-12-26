# 测试说明

## WebSocket 单聊冒烟（不依赖 Redis）

用于快速验证：`SINGLE_CHAT` 落库 ACK（saved）与接收 ACK（ack_receive）联动是否正常，以及 DB 状态是否推进到 `RECEIVED`。

补充说明：
- 当前离线补发逻辑在服务端 `AUTH` 处理后触发；即使已在握手阶段完成鉴权，仍会在收到 `AUTH` 帧后执行离线补发。

前置条件：
- 服务端已启动（HTTP `:8080` + WS `:9001/ws`）
- 本机安装 JDK（需要 `javac` / `java`）
- （可选）本机可访问 MySQL（用于校验 `t_message.status`）

运行：
- 跑全部场景（默认）：`basic + idempotency + offline`
  - `powershell -ExecutionPolicy Bypass -File scripts/ws-smoke-test/run.ps1`
- 指定场景：
  - `powershell -ExecutionPolicy Bypass -File scripts/ws-smoke-test/run.ps1 -Scenario basic`
  - `powershell -ExecutionPolicy Bypass -File scripts/ws-smoke-test/run.ps1 -Scenario idempotency`
  - `powershell -ExecutionPolicy Bypass -File scripts/ws-smoke-test/run.ps1 -Scenario offline`
- 同时校验 DB 状态（示例使用 root 账号）：
  - `powershell -ExecutionPolicy Bypass -File scripts/ws-smoke-test/run.ps1 -CheckDb -DbPassword "<your_password>"`
  - DB 校验会把每个场景对应 `serverMsgId` 的 `t_message.status` 回填到输出 JSON 中

可调参数：
- `-WsUrl`：默认 `ws://127.0.0.1:9001/ws`
- `-JwtSecret`：默认使用 `application.yml` 的默认值（可覆盖）
- `-UserA/-UserB`：默认 `10001/10002`
- `-TimeoutMs`：默认 `8000`
- `-Scenario`：默认 `all`

输出字段说明：
- `ok`：整体是否通过（任一场景失败则为 false）
- `scenarios`：每个场景的结果（包含 `clientMsgId/serverMsgId/expected` 等）
- `scenarios.*.dbStatus`：开启 `-CheckDb` 时回填（例如 `5=RECEIVED`、`6=DROPPED`）
- `explain`：ACK 与 DB 状态的含义（用于解释“为什么会这样返回”）
