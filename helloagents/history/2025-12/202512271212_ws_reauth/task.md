# 任务清单

方案包：202512271212_ws_reauth  
范围：WebSocket reauth（续期）+ 冒烟测试补齐  
状态：进行中

## 任务列表

- [√] 服务端：新增 `REAUTH` 帧，使用新 accessToken 刷新当前连接的过期时间（expMs）
- [√] 服务端：`REAUTH` 校验 uid 必须与当前连接一致；失败返回错误并断开
- [√] 服务端：REAUTH 不触发离线补发（仅续期）
- [√] 安全：WS 收帧日志脱敏 token（避免打印明文）
- [√] 测试：扩展 `scripts/ws-smoke-test` 的 `auth` 场景，覆盖 reauth（续期后不应在旧 token 到期时断开）
- [√] 知识库：更新 `helloagents/wiki/testing.md` 增加 reauth 说明
- [√] 自测：跑 `run.ps1 -Scenario auth` 与 `run.ps1 -Scenario all -CheckDb`（在新起的 9002 端口实例验证）

## 验收标准

- `REAUTH` 生效：在旧 token 过期后仍能 `PING -> PONG`，直到新 token 过期才 `token_expired`
- 脚本输出 steps 可读且不泄露 token 明文

## Task 变动记录

（如实现与预期不同，在此追加记录）
