# task - WS 慢消费者背压治理（硬化方案）

## 执行任务

- [√] 复盘现有实测：确认慢消费者可导致内存上涨与延迟爆炸（见 `helloagents/wiki/perf_eval_20260108.md`）
- [?] 上线前回归：慢消费者重放 + 基础冒烟（当前环境缺少 MySQL 密码，后端无法启动；待补齐 `IM_MYSQL_PASSWORD` 后回归）
- [√] 落地 L1 初版：在 push 写路径对 critical 类型（`ERROR/CALL_*`）采用“不可写则断开”；普通类型不可写则 best-effort 丢弃
- [√] 增加可观测统计：产生日志事件（踢慢连接/critical 不可写断开），支持 grep 聚合
- [ ] 形成验收报告模板：慢端触发次数、影响 uid 数、P99 与内存对比（修复前/后）

## 交付物

- 代码变更（L0/L1/L2 按优先级逐步合入）
- `helloagents/wiki/perf_eval_*.md` 增量记录（复现命令 + 通过标准 + 实测数据）
