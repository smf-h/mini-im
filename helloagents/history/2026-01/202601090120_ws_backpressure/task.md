# task - WS 慢消费者背压保护（轻量迭代）

## 任务清单

- [√] 为 Netty 子连接设置 `WRITE_BUFFER_WATER_MARK`
- [√] 在统一写出 `WsWriter` 上增加 `isWritable` 降级（拒绝继续写入）
- [√] 增加 `WsBackpressureHandler`：持续 unwritable 自动断开 + 产生日志事件
- [√] 更新知识库：补齐配置键、回归命令与通过标准

## 回归验证（建议）

前置：后端可启动（若 MySQL 有密码需设置 `IM_MYSQL_PASSWORD`）。

- 慢消费者重放：
  - `powershell -ExecutionPolicy Bypass -File scripts/ws-load-test/run.ps1 -Mode single_e2e -WsUrls ws://127.0.0.1:9001/ws -Clients 200 -DurationSeconds 60 -MsgIntervalMs 100 -SlowConsumerPct 30 -SlowConsumerDelayMs 5000`
- 观察点：
  - 进程 WorkingSet/GC 曲线不再持续线性爬升
  - 普通收端 E2E P99 < 5s（慢端允许被踢下线或单独劣化）
  - 日志出现 `ws backpressure: closing slow consumer channel`（说明踢慢端生效）

