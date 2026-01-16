# WS 压测与可靠性快测（单机）- how

## 工具选择

- 优先：k6（WebSocket 友好，指标丰富）
- 受限网络（github.com 不可达）时：使用 Java17 `java.net.http.WebSocket` 写成脚本级压测器

## 测试覆盖

- 可靠性：复用仓库自带 `ws-smoke-test`，覆盖 ACK/离线/补发/幂等
- 并发连接：Java 压测器 `CONNECT` 模式
- 即时性：Java 压测器 `PING` 模式
- 重启演练：在 `CONNECT + reconnect` 模式下重启网关实例，观察连接恢复与重连风暴风险

