# 技术设计: 单聊进一步提速（DB 线程池 × 连接池对齐 + 过载可控）

## 技术方案

### 现状线索（为什么怀疑是“线程池/连接池不对齐”）

- `imDbExecutor` 当前默认：`core=8,max=32,queue=10000`（每实例），5 实例时理论上最多 160 个 DB 线程同时争用 DB 资源。
- `spring.datasource.hikari.*` 未显式配置（`src/main/resources/application.yml`），默认最大连接数通常是 10（每实例），5 实例合计 50。
- 当 DB 线程数明显大于可用连接数时，常见现象是：
  - DB executor 队列与线程都“很忙”，但吞吐不增反降；
  - tail latency 抖动变大（线程争用 + 连接等待 + CPU 上下文切换）。

> 注意：目前 repo 没有 Actuator/Micrometer，无法直接读到 Hikari 指标；因此以“压测矩阵 + 分段耗时（dbQueueMs/totalMs）+ 失败率”做近似量化，并在必要时补充最小侵入的连接等待打点。

---

## 方案对比

### 方案 1（推荐）：对齐并“定额化”DB 并发（线程=连接），队列收紧为可控失败

**做法：**
1) 显式配置 Hikari：
- `spring.datasource.hikari.maximum-pool-size = P`
- `spring.datasource.hikari.minimum-idle = min(P, 2~4)`
- `spring.datasource.hikari.connection-timeout = 300~800ms`（避免无限等连接）

2) 把 `imDbExecutor` 变成“更可控”的固定并发：
- `core=max=P`（与 Hikari 对齐）
- `queueCapacity` 从 10000 降到一个较小值（例如 200~2000），并在 reject 时返回 `server_busy`

**优点：**
- 最贴近“尾延迟优先”的目标：过载时不靠深队列堆几十秒
- 避免线程>连接导致的空转与抖动

**缺点：**
- offered load 超过能力时，失败率会更早上升（但你已接受 <5%）

### 方案 2：只增加 Hikari pool size（保守）

**做法：**
- 只把 `maximum-pool-size` 提升到一个足够值（例如 20/30），不动 `imDbExecutor`

**优点：**
- 变更更小，风险低

**缺点：**
- 线程争用、队列放大仍可能存在；对 p99 收敛不一定有效

### 方案 3：补充连接等待（borrow）打点后再精调（更准确但多一步）

**做法：**
- 为 DB 访问路径增加“获取连接耗时”的最小打点（例如 DataSource wrapper / 拦截器），区分：
  - dbQueue（executor wait）
  - connWait（borrow wait）
  - sqlExec（真正执行）

**优点：**
- 根因量化最清晰

**缺点：**
- 需要额外代码与测试，周期更长

---

## 推荐落地参数（起步值）

以单机 5 实例为前提，建议先把“总连接数”控制在 DB 可承受范围：

- 假设 MySQL `max_connections >= 150`（默认常见值），建议：
  - 每实例 `Hikari max = 12~16`
  - 每实例 `imDbExecutor core=max = Hikari max`
  - 每实例 `imDbExecutor queueCapacity = 500~2000`

若 CPU 核数较少（例如 8 核），建议把每实例并发再下调（例如 max=8~12），以避免单机线程争用。

---

## 压测验证矩阵（可复现）

目标：在相同 offered load 下，比较不同配置对 E2E/错误率/分段耗时的影响。

### 基线（当前）
- `im.executors.db.core=8,max=32,queue=10000`
- Hikari 未显式（默认）

### Matrix（每次只改一组）
1) **对齐版（推荐）**
- Hikari `max=12/16`
- `imDbExecutor core=max=12/16`
- `dbQueueCapacity=500/2000`

2) **只调 Hikari**
- Hikari `max=20/30`，`imDbExecutor` 不变

### 观测指标
- 客户端：E2E `p50/p95/p99`、`wsError%`、`attempted/sent`
- 服务端：`ws_perf single_chat.dbQueueMs/totalMs/dbToEventLoopMs` 分位数

---

## 实施清单（将体现在 task.md）

1) 在压测脚本透传 Hikari 参数（避免改动生产默认）
2) 允许通过脚本调 `imDbExecutor` 的拒绝策略/队列大小（过载可控）
3) 跑矩阵并写入知识库回归报告

