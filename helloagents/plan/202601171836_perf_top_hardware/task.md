# 任务清单（全链路性能顶尖化）

## 0. 前置与基线

- [ ] 固化压测口径：open-loop + sendModel（spread/burst）+ drainMs + errorsByReason
- [ ] 跑一轮基线：单聊（5000）、群聊（默认参数）、连接（50000）并记录 runDir

## 1. 噪声与可比性修复

- [ ] 修复单机多 JVM 下 MyBatis-Plus ASSIGN_ID 冲突导致的 DuplicateKey/internal_error（若仍存在）
- [ ] 清理/隔离跨 run 的幂等污染与关机噪声（确保错误率可解释）

## 2. 单聊链路优化

- [ ] 线程/连接池配额审计与自动对齐（不同实例数下保持总并发稳定）
- [ ] 关键分段（queue/dbQueue/dbToEventLoop）逐项压缩，目标是把排队型延迟消到最低

## 3. 群聊链路优化

- [ ] group_dispatch 批处理与跨实例 publish 合并优化（减少 per-user 操作）
- [ ] 策略 auto/push/notify 的阈值与降级路径压测验证（含慢消费者/背压）

## 4. Redis 宕机/抖动下的功能与降级

- [ ] 路由/幂等/PubSub 不可用时的行为明确化（返回码/降级策略/保护阀）
- [ ] Redis down 专测：单聊/群聊/ACK/登录踢人等关键路径不崩溃，错误率可控

## 5. 归因与报告

- [ ] 对每个“突破性改动”做消融对照（runDir + 指标表）
- [ ] 更新 Wiki：最终性能指标（延迟/吞吐/错误率/保存率）+ 关键改动归因
- [ ] 更新 CHANGELOG + 提交代码（按提交拆分，便于回滚/对照）

