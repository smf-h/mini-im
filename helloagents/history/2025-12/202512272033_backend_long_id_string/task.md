# 轻量迭代：后端 Long ID 字符串化

- [√] 为 Jackson 配置 `Long/long` 的“JS 安全整数”序列化（超出 `2^53-1` 输出字符串）
- [√] 更新 `scripts/ws-smoke-test` 兼容 `"id":"123"` 形式
- [√] 构建：`mvn -DskipTests package`
- [√] 冒烟：`powershell -ExecutionPolicy Bypass -File scripts/ws-smoke-test/run.ps1 -Scenario auth -AuthTokenTtlSeconds 2`
- [√] 冒烟：`powershell -ExecutionPolicy Bypass -File scripts/ws-smoke-test/run.ps1 -Scenario friend_request`
- [√] 同步知识库与变更记录，归档方案包
