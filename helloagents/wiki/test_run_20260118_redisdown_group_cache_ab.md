# Redis down：群聊链路“缓存 fail-fast + 本机兜底”A/B

目的：在 Redis 不可用（模拟 `RedisPort=1`）时，避免群聊主链路被 Redis 超时放大成“秒级排队”，并验证把关键 cache 做成“fail-fast + 本机兜底”后，群聊 E2E 是否能明显收敛。

口径（两次对照保持一致）：
- 5 实例，`AutoTuneLocalThreads=true`
- Redis down：`-RedisPort 1 -SkipRedisCheck`（命令/连接超时默认 500ms）
- 群聊（`clients=200`，`senders=20`，`msgIntervalMs=50`，`duration=60s`，`receiverSamplePct=30`）
- `LoadSendModel=spread`
- 仅改变：群成员/JSON cache 的 fail-fast + 本机兜底

## A：baseline（旧实现，Redis down 秒级尾延迟）

runDir：`logs/ws-cluster-5x-test_20260118_003720/`（旧代码，手动 `-SkipSmoke`）

群聊（`group_push_e2e.json`）：
- sentPerSec=`403.07`
- wsError=`0`
- E2E p50/p95/p99=`55/3504/4963 ms`

ws_perf（`ws_perf_summary_gw1.json`，group_chat）：
- memberCacheGetMs p95/p99=`36/41 ms`
- queueMs p95/p99=`54/192 ms`
- dbQueueMs p95/p99=`30/48 ms`

## B：改进后（fail-fast + 本机兜底，Redis down 尾延迟明显收敛）

runDir：`logs/ws-cluster-5x-test_20260118_005605/`

群聊（`group_push_e2e.json`）：
- sentPerSec=`404.00`
- wsError=`0`
- E2E p50/p95/p99=`14/23/917 ms`

ws_perf（`ws_perf_summary_gw1.json`，group_chat）：
- memberCacheGetMs p95/p99=`0/0 ms`
- queueMs p95/p99=`0/0 ms`
- dbQueueMs p95/p99=`4/7 ms`

## 结论

- Redis down 场景下，“群成员缓存读取”会成为放大器：每条消息在 cache 层等待 10^2 ms 级超时，会把尾延迟抬到秒级。
- 对 cache 做 fail-fast + 本机兜底后，群聊 p95 从秒级收敛到几十毫秒量级，p99 也明显下降（仍可能受跨实例路由/推送不可用影响而出现尾部拖长）。

