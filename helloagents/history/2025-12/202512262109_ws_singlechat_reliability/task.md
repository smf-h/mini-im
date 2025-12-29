# 任务清单（追认补档）

方案包：202512262109_ws_singlechat_reliability  
分支：feat/ws-ack-scheduler  
类型：功能 & 测试  
状态：✅ 已完成（追认补档）

## 背景与目标

本方案包用于对已完成的“WS 单聊可靠性”相关改动做追认归档，补齐 helloagents/history 索引与可追溯记录。

目标：
- 单聊链路统一走 WebSocket（SINGLE_CHAT / ACK / AUTH）
- 落库确认（ACK(saved)）与接收确认（ACK_RECEIVED）形成可追溯的状态推进
- 离线与超时场景具备可验证的补发兜底
- 提供可复用的一键冒烟脚本，输出每步消息内容与原因说明

## 任务清单

- [√] 单聊：发件人 `SINGLE_CHAT` → 服务端落库 → 回 `ACK(saved)`（clientMsgId 幂等）
- [√] 单聊：收件人收到后回 `ACK(ack_receive/received)` → 服务端更新 DB 状态为 `RECEIVED`
- [√] 离线：收件人离线时 DB 标记 `DROPPED`；收件人上线（AUTH）后补发并推进 `RECEIVED`
- [√] 定时任务：`WsCron` 扫描超时未收到 ACK_RECEIVED 的 `SAVED` 消息进行补发；离线则更新为 `DROPPED`
- [√] 开发降级：Redis 不可用时，`SessionRegistry` 路由写入降级为 warn，不阻断单机开发 WS 链路
- [√] 冒烟脚本：`scripts/ws-smoke-test` 支持 `basic/idempotency/offline/cron` 并输出 `steps`（每步 raw JSON）
- [√] 知识库：补充测试说明与脚本使用方式（`helloagents/wiki/testing.md`）

## 验证与证据

- 冒烟命令：`scripts/ws-smoke-test/run.ps1 -Scenario all -CheckDb`
- 预期：`basic/idempotency/offline/cron` 全通过，DB `t_message.status` 终态为 `RECEIVED(5)`

## Task 变动记录

> 说明：本条为“追认补档”，实际代码已先行提交；此处仅补齐计划与执行记录。

- 2025-12-26：为满足 “未收到 ACK_RECEIVED 才重发” 的口径，投递成功不再写 `DELIVERED`，消息保持 `SAVED` 直到收到 `ACK_RECEIVED`

## 关联提交（已完成）

- `42e58af` feat(ws): ack-receive & retry cron
- `74a080f` test(ws): add ws smoke test script
- `f1ae1ed` docs: add ws smoke test guide
- `85df6dc` test(ws): expand smoke scenarios; fix offline resend on AUTH
- `c071c92` test(ws): add cron resend scenario & step traces

