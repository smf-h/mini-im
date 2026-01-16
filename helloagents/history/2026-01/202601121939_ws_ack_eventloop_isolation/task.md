# 任务清单：ACK 回切隔离（eventLoop 减负）

> 目标：减少 DB 完成后的 eventLoop 回切排队（`dbToEventLoopMs`），降低单聊/群聊 E2E 长尾。

## 研发任务

- [√] 代码改造：`WsWriter` 增加 `writeAck(Channel, ...)` 与可记录 eventLoop 排队延迟的写方法
- [√] 代码改造：`WsSingleChatHandler` 去掉 `dbFuture.whenComplete -> ctx.executor().execute(...)`，回调线程直接后置处理，写回由 `WsWriter` 回切
- [-] 代码改造：`WsGroupChatHandler` 去掉 `future.whenComplete -> ctx.executor().execute(...)`（已验证会放大群聊写洪峰并拖垮 eventLoop，暂不做；见 how.md）
- [√] 指标校验：确保 `ws_perf single_chat.dbToEventLoopMs` 仍可反映“写回进入 eventLoop 的排队”（不通过改口径作弊）

## 验证任务（可复现）

- [√] `mvn test`（阻断性）
- [√] `mvn -DskipTests package`（构建 jar）
- [√] 5 实例压测：`scripts/ws-cluster-5x-test/run.ps1`（至少跑 `clients=500`、`5000`；`50000` 只跑 connect）
- [√] perf 解析：`scripts/ws-perf-tools/parse-ws-perf.ps1` 生成 summary（关注 single_chat / group_chat）
- [√] 慢消费者回归：`scripts/ws-backpressure-multi-test/run.ps1`（验证踢慢连接闭环仍生效）

## 知识库同步

- [√] 更新 `helloagents/wiki/test_run_20260111.md`：新增本次对照数据与结论
- [√] 更新 `helloagents/wiki/modules/gateway.md`：补充“eventLoop 隔离/ACK 回切”注意事项
- [√] 更新 `helloagents/CHANGELOG.md`

## 收尾

- [√] 迁移方案包：移动到 `helloagents/history/2026-01/` 并更新 `helloagents/history/index.md`
