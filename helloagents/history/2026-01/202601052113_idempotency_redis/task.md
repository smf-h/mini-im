# Redis 幂等强化（ClientMsgId）- 任务清单

## 1. 现状核对
- [√] 1.1 盘点所有使用 `ClientMsgIdIdempotency` 的入口（单聊/群聊/好友/其它写接口）
- [√] 1.2 明确当前 Redis key/value 结构与 TTL（与预期 1800s 对齐）

## 2. 配置与口径统一
- [√] 2.1 新增幂等配置项：`ttl-seconds=1800`（可覆盖默认）
- [√] 2.2 统一 key 构成：`{userId}:{biz}:{clientMsgId}`（群聊纳入 groupId）
- [√] 2.3 本机缓存过期策略调整为 `expireAfterWrite`（或给出理由继续 `expireAfterAccess`）

## 3. Redis 写入优化（可选）
- [-] 3.1 评估是否引入 Lua 一次完成 set/get（减少往返）
- [-] 3.2 若引入：补充脚本与测试，确保兼容 Redis Cluster（脚本不使用跨 key 操作）

## 4. 调用点改造与一致性
- [√] 4.1 单聊发送：统一 key 前缀与 TTL 配置读取
- [√] 4.2 群聊发送：统一 key 前缀与 TTL 配置读取（含 groupId）
- [√] 4.3 好友申请：统一 key 前缀与 TTL 配置读取
- [√] 4.4 失败释放：所有落库失败路径确保 `remove(key)`（避免长时间占位）

## 5. 文档与验证
- [√] 5.1 更新 `helloagents/wiki/modules/gateway.md`：幂等窗口、降级语义、风险边界
- [√] 5.2 更新 `helloagents/CHANGELOG.md`
- [√] 5.3 测试：新增/补齐单测与必要的集成测试用例
