# 轻量迭代：WS 补发/扫库噪声收敛

目标：减少重复补发与日志噪声，补齐失败可追踪上下文，并对齐配置/文档。

任务清单：
- [√] 给 WS `AUTH` 重复触发补发增加门禁（同一连接仅补发一次）
- [√] 补发写入失败/异常日志补齐 `source/userId/serverMsgId/clientMsgId/groupId` 等上下文（并避免刷屏）
- [√] 对齐配置项：`im.cron.scan-dropped.*` → `im.cron.resend.*`（保留兼容读取）
- [√] 同步更新知识库（`helloagents/wiki/testing.md` 等）与配置示例（`application*.yml`）
- [√] 本地验证：`mvn test`、`npm -C frontend run build`
