# 变更提案: 单聊进一步提速（DB 线程池 × 连接池对齐 + 过载可控）

## 需求背景

当前单聊在常规负载（open-loop、`msgIntervalMs=3000`）已能做到亚秒级 E2E，但 `ws_perf single_chat` 仍可观察到 DB 侧排队抖动（`dbQueueMs`）与实例间资源竞争迹象。为进一步降低 p95/p99 并稳定成功率，需要把“DB 线程池并发”与“JDBC 连接池并发”对齐，避免：

- DB 线程池线程数过大 → 大量线程争抢有限连接（隐藏的 connection borrow wait），增加上下文切换与抖动；
- DB 线程池队列过大 → 过载时用排队偿债，尾延迟被放大；
- 多实例（5x）下资源总量被放大（线程/连接总数 = 单实例配置 × 实例数），单机压测更容易进入“抖动区”。

你已确认的业务取舍：
- `ACK(saved)` 语义保持：消息落库成功即可 ACK；
- `t_single_chat.updated_at` 已允许异步最终一致（≤1s），不再阻塞主链路；
- 可接受 `server_busy/too_many_requests` 在过载时小比例出现（目标 < 5%），以换取整体延迟与稳定性。

## 变更内容

1. **DB executor 与 Hikari 连接池对齐**
   - 明确设置 `spring.datasource.hikari.maximum-pool-size/minimum-idle/connection-timeout`；
   - 调整 `im.executors.db.core/max` 与 Hikari 最大连接数对齐（每实例），避免线程空转与争用。

2. **过载从“排队”改为“可控失败”**
   - 适度降低 DB executor queue capacity（避免 10k 深队列），并在拒绝时映射为 `server_busy`；
   - 与现有 inbound-queue / 用户级限流配合，确保过载时延迟不雪崩。

3. **以可复现压测矩阵验证（open-loop）**
   - 维持 offered load 可比（固定速率），对比不同“线程/连接/队列”组合对 `E2E p95/p99`、`wsError`、`dbQueueMs` 的影响。

## 影响范围

- **模块:**
  - `config/executors`（DB 线程池策略与拒绝处理）
  - `spring.datasource.hikari`（连接池参数显式化）
  - `scripts/ws-cluster-5x-test`（压测脚本透传参数，保证可复现）
- **文件（预期）:**
  - `src/main/java/com/miniim/config/ImExecutorsConfig.java`
  - `src/main/resources/application.env.yml` / `src/main/resources/application.env.values.yml`（或仅压测脚本透传）
  - `scripts/ws-cluster-5x-test/run.ps1`

## 核心场景

### 需求: p95/p99 进一步下降且更稳定
**模块:** gateway/ws + config

#### 场景: 5 实例 + open-loop 常规负载（clients=5000, msgIntervalMs=3000）
- 条件：offered load 固定（open-loop），跨实例分布正常
- 预期：
  - E2E p95/p99 进一步下降（或至少波动收敛）
  - `wsError`（含 `too_many_requests/server_busy/wsError`）可控（目标 < 5%）
  - `ws_perf single_chat.dbQueueMs` 与 `totalMs` 分位数收敛

### 需求: 过载时不出现“排队雪崩”
**模块:** config + gateway/ws

#### 场景: offered load 超过系统可持续吞吐（burst）
- 条件：open-loop 提升速率至过载
- 预期：
  - 延迟不线性爬升到几十秒（或爬升明显减轻）
  - 失败以 `server_busy/too_many_requests` 体现，且只影响异常发送者为主

## 风险评估

- **风险:** 连接池/线程池调参不当导致吞吐下降或错误率上升
  - **缓解:** 采用压测矩阵逐步调参；每次只改一组关键参数；记录 offered load 与成功率
- **风险:** 降低 DB executor queue capacity 可能带来更多 `server_busy`
  - **缓解:** 以“尾延迟优先”为目标，结合客户端退避+抖动；必要时分层队列（重要请求优先）

