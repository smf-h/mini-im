# 任务清单

方案包：202512271012_auth_chain_test  
范围：鉴权全链路测试（WS + HTTP）与可观测性增强  
状态：进行中

## 任务列表

- [√] 新增/扩展脚本：`auth` 场景（WS 握手鉴权 + WS AUTH 帧鉴权）
- [√] 扩展脚本输出：每步打印消息内容（含 raw JSON，token 脱敏）
- [√] 扩展脚本输出：按 serverMsgId 打印 DB 记录（t_message）与状态聚合
- [√] 扩展脚本输出：打印 Redis 数据（如可用；不可用则跳过并说明）
- [√] 更新知识库：`helloagents/wiki/testing.md` 增加鉴权全链路说明
- [√] 自测：跑 `Scenario auth/basic`，验证输出可读且不泄露敏感信息

## 验收标准

- 可以一键运行：在服务端启动后执行脚本得到可读 JSON 输出
- 输出包含：
  - 场景结果 ok/失败原因
  - 关键步骤 steps（含 raw JSON、type、clientMsgId/serverMsgId、body 等）
  - DB/Redis/关键变量信息（按“合适时机”输出，且默认脱敏）
- 不在日志/输出中泄露 token 明文

## Task 变动记录

- 2025-12-27：本次优先覆盖 WS 鉴权链路；HTTP 登录/refresh/verify 的“全链路鉴权测试”暂未纳入脚本（后续如需要再补充）。
