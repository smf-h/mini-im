# 执行方案（全链路性能/稳定性）

## 总体策略

1) **先把测试做对**：用 open-loop + sendModel（spread/burst）把“工作负载形态”显式化；统一记录 runDir，确保每次结论可追溯。
2) **分层定位瓶颈**：对每条链路同时看
   - 端到端（E2E）分位数
   - 服务端 ws_perf 分段（queue/dbQueue/dbToEventLoop/dispatch/push/redis_pubsub…）
   - 错误桶（errorsByReason）
   - 保存率（ackSavedRate / sentPerSec / recvPerSec）
3) **只做不改变大逻辑的高 ROI 改动**：线程/队列/背压/批处理/缓存/降级开关/观测增强；必要时补索引，但不改业务语义。
4) **用消融实验归因**：每次只改一类因素，复测一次并记录（必要时 3 次均值），把“突破性改善”对应到具体提交。

## 测试矩阵（核心）

### A. 单聊
- ws-cluster：`Instances` sweep（1..N）+ `AutoTuneLocalThreads` on/off + `LoadSendModel` spread/burst
- 负载：`clients=5000`、`open-loop`、`msgIntervalMs=3000`、`duration=60s`
- 指标：E2E p50/p95/p99、sent/s、recv/s、wsError、errorsByReason、ws_perf.single_chat.*

### B. 群聊
- `scripts/ws-group-load-test/run.ps1`（push/notify/auto 三种策略）
- 负载：中小群（200~2000）与更大群（可分档）
- 指标：E2E、重复/乱序、wsError、ws_perf.group_chat/group_dispatch/push/redis_pubsub

### C. Redis 宕机/抖动
- 场景：
  - Redis down：路由 SSOT 不可用、Pub/Sub 不可用、幂等不可用（需降级策略）
  - Redis high-latency：模拟 50~200ms 延迟（如果可控）
- 目标：服务稳定（不崩溃、不死锁），功能按预期降级（明确哪些能力不可用/返回什么错误），错误率受控。

## 重点优化方向（优先级）

1) **测试形态显式化**（已完成：sendModel=spread|burst + cluster 透传）
2) **跨实例/多 JVM 的噪声消除**（例如 Snowflake/幂等污染/关机噪声）
3) **线程/连接池配额与背压**：避免“实例数上去→总并发失控/稀释→排队雪崩”
4) **群聊下发路径的批处理与跨实例 publish 合并**（减少 per-user 调度）
5) **Redis 宕机降级**：幂等/路由/补发/群聊策略的 fail-open/fail-closed 边界明确

## 输出与验收

最终输出一个 Wiki 报告，包含：
- 单聊/群聊/ACK/redis_down 的对照表（runDir 可回放）
- 每项关键改动的“前后指标变化 + 归因”
- 现硬件下的“推荐实例数/线程配额/连接池配额”

