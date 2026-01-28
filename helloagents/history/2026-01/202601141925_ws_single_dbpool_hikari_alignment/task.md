# 任务清单: 单聊进一步提速（DB 线程池 × 连接池对齐 + 过载可控）

目录: `helloagents/plan/202601141925_ws_single_dbpool_hikari_alignment/`

---

## 1. 参数透传与基线校准
- [√] 1.1 在 `scripts/ws-cluster-5x-test/run.ps1` 中新增 Hikari 参数透传（`-JdbcMaxPoolSize/-JdbcMinIdle/-JdbcConnectionTimeoutMs`），并修复多实例启动参数截断/端口探测问题
- [√] 1.2 固定 open-loop 基线回归（clients=5000,msgIntervalMs=3000,60s×3），产物见 `logs/ws-cluster-5x-test_20260114_212413/`

## 2. 线程池/连接池对齐（推荐方案）
- [√] 2.1 `imDbExecutor` 采用 `AbortPolicy`，并在单聊入口捕获 `RejectedExecutionException` 映射为 `ERROR server_busy`，作为“过载可控失败”基础
- [√] 2.2 运行矩阵对照（max=12/16 + queue=500/2000），结论与数据见 `helloagents/wiki/test_run_20260114_singlechat_dbpool_hikari_alignment.md`

## 3. 安全检查
- [√] 3.1 安全检查：无生产环境操作/无明文密钥写入；新增的拒绝策略仅影响过载行为（返回 `server_busy`），不涉及权限提升/破坏性操作

## 4. 测试与报告
- [√] 4.1 输出回归报告：`helloagents/wiki/test_run_20260114_singlechat_dbpool_hikari_alignment.md`
- [√] 4.2 更新 `helloagents/CHANGELOG.md`
