# 测试说明

## WebSocket 单聊冒烟（不依赖 Redis）

用于快速验证：`SINGLE_CHAT` 落库 ACK（saved）与接收 ACK（ack_receive）联动是否正常，以及 DB 状态是否推进到 `RECEIVED`。

前置条件：
- 服务端已启动（HTTP `:8080` + WS `:9001/ws`）
- 本机安装 JDK（需要 `javac` / `java`）
- （可选）本机可访问 MySQL（用于校验 `t_message.status`）

运行：
- 仅验证 WS 链路：
  - `powershell -ExecutionPolicy Bypass -File scripts/ws-smoke-test/run.ps1`
- 同时校验 DB 状态（示例使用 root 账号）：
  - `powershell -ExecutionPolicy Bypass -File scripts/ws-smoke-test/run.ps1 -CheckDb -DbPassword "<your_password>"`

可调参数：
- `-WsUrl`：默认 `ws://127.0.0.1:9001/ws`
- `-JwtSecret`：默认使用 `application.yml` 的默认值（可覆盖）
- `-UserA/-UserB`：默认 `10001/10002`
- `-TimeoutMs`：默认 `8000`

