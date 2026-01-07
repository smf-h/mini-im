# 技术设计: 待提交代码质量审查与收敛

## 技术方案

### 核心目标
1. **把提交变成可控的“变更集”**：范围清晰、可分批、可回滚。
2. **提升关键链路的可观测性**：异常不被静默吞掉，日志与错误码可定位。
3. **隔离环境差异**：dev/prod 配置、日志级别、客户端地址不混用。
4. **保证可验证**：构建+测试覆盖关键路径，减少“上线才发现”的风险。

### 方案对比（复杂任务）

#### 方案1（最小变更收敛-推荐）
**思路:** 不推翻现有实现，先做“提交卫生 + 关键风险修复”，再做“体验/结构优化”。
- **优点:** 风险最低；可快速形成可提交版本；适合当前变更量大的现状。
- **缺点:** 可能留下结构性债务（需要后续再做重构型提交）。
- **适用:** 需要尽快收敛并提交，同时确保线上风险可控。

#### 方案2（回滚/重做-高成本）
**思路:** 回到干净基线，把必要功能逐个 cherry-pick/重做，并在每一步完成测试。
- **优点:** 历史最干净；每个提交天然可追溯。
- **缺点:** 成本高、耗时；容易在重做过程中引入新偏差。
- **适用:** 若确认当前变更夹带大量误入文件/方向不一致且难以在原基础上收敛。

#### 方案3（保留现状+仅加验证-不推荐）
**思路:** 不做范围清理与异常处理调整，只增加测试/构建验证。
- **优点:** 开发成本最低。
- **缺点:** 风险无法根治；误入文件与吞异常会长期存在。
- **适用:** 仅在时间极端紧张、且可接受未来返工时考虑。

**推荐选择:** 方案1（最小变更收敛）

## 已发现问题快照（基于当前工作区扫描）

> 注：本节仅记录“已确定存在/高度可疑”的问题点，完整逐文件审查会在开发实施阶段按 task.md 执行。

### 提交卫生/误入风险
- `miniprogram/project.private.config.json`：未跟踪私有配置文件，存在误提交风险（建议纳入 `.gitignore`）。
- `miniprogram/miniprogram/**`：出现疑似模板/示例工程的重复目录与大量图片资源，需确认是否应入库。

### 调试痕迹
- 小程序存在 `console.log`：例如 `miniprogram/pages/index/index.js`、`miniprogram/miniprogram/pages/**`、`miniprogram/miniprogram/pages/example/index.js`。
- 脚本存在 `System.out.println`：例如 `scripts/ws-smoke-test/WsSmokeTest.java`、`scripts/ws-cluster-smoke-test/WsClusterSmokeTest.java`（若为调试输出建议改为可控日志或在文档中明确“仅脚本可输出”）。

### 异常处理（空 catch/忽略异常）
- 扫描到 17 处空 `catch`（15 处为 ignore/ignored），涉及鉴权/WS/缓存关键路径：
  - `src/main/java/com/miniim/auth/web/AccessTokenInterceptor.java`
  - `src/main/java/com/miniim/gateway/ws/WsGroupChatHandler.java`
  - `src/main/java/com/miniim/gateway/ws/WsSingleChatHandler.java`
  - `src/main/java/com/miniim/gateway/session/SessionRegistry.java`
  - `src/main/java/com/miniim/common/cache/RedisJsonCache.java`
  - 以及部分控制器/缓存实现（详见开发实施阶段逐项处理）
- `src/main/java/com/miniim/gateway/ws/WsChannelSerialQueue.java`：存在 `catch (Throwable)`，需确认是否会掩盖致命错误与线程中断语义。

### 配置与可观测性
- `src/main/resources/application.yml`：默认开启 MyBatis 相关 debug logger，建议下沉到 dev profile。
- 文档编码/显示：`helloagents/CHANGELOG.md` 检测到 UTF-8 BOM 与混合行尾迹象；另有终端输出乱码信号，建议统一编码与行尾策略（`.gitattributes`）。

## 架构决策 ADR

### ADR-001: 提交拆分策略（先卫生、后逻辑）
**上下文:** 当前变更覆盖面大，且存在未跟踪大目录与私有文件风险。
**决策:** 以“提交卫生”为第一优先级：先移除误入文件与调试痕迹，再做关键逻辑修复，最后做文档/可选优化。
**替代方案:** 直接一次性提交全部变更 → 拒绝原因: 难回滚、难定位、review 成本高。
**影响:** 提交次数增加，但每次更可控，review 与回滚成本显著下降。

### ADR-002: 关键链路异常处理策略（不吞异常/显式降级）
**上下文:** 扫描到多个空 `catch`，涉及鉴权/WS/缓存等关键路径。
**决策:** 对关键链路禁止“无声吞异常”；允许吞掉的场景必须满足：
1) 业务语义允许忽略；2) 不影响一致性；3) 有最低限度日志或指标；4) 有测试覆盖或明确注释说明。
**替代方案:** 保持现状 → 拒绝原因: 静默失败难定位、易形成隐性线上故障。
**影响:** 日志可能略增，但换来可定位与更稳定的行为边界。

### ADR-003: 配置与日志级别（默认非 debug，按 profile 开启）
**上下文:** `application.yml` 默认开启部分 debug logger；客户端存在本地地址硬编码。
**决策:** 默认配置保持“生产友好”；dev 环境通过 profile 或 `application.env.yml` 注入开启 debug；客户端按环境切换 baseUrl/wsUrl。
**替代方案:** 继续默认 debug 或硬编码地址 → 拒绝原因: 生产噪声、潜在信息泄漏、环境不可移植。
**影响:** 需要补充配置说明与示例，但长期维护收益高。

### ADR-004: 统一行尾/编码（通过 .gitattributes）
**上下文:** 出现 LF/CRLF 相关警告与 BOM/混合换行现象。
**决策:** 引入 `.gitattributes` 统一 text 文件行尾策略（推荐 `LF`），并逐步修复少量关键文档的编码一致性。
**替代方案:** 依赖各自本地 git 配置 → 拒绝原因: 团队协作下 diff 噪声持续存在。
**影响:** 需要一次性制定规则，但可以显著减少未来噪声与冲突。

## 安全与性能
- **安全:** 排查待提交内容中是否包含真实密钥/私有配置；对 token/log 做脱敏；生产默认不启 debug。
- **性能:** 对 WS fanout、缓存降级、重试/重发策略进行边界验证，避免放大风暴（如重连/重发叠加）。

## 测试与部署
- **测试策略:** 以“关键链路优先”为原则：
  - 后端：鉴权（login/refresh/sessionVersion）、WS 握手与 AUTH、消息幂等、群聊 fanout、缓存降级、限流等。
  - 前端/小程序：构建检查、关键页面基础交互（登录、连接、发送/接收）。
- **部署策略:** 按提交拆分逐步合入；每个提交附带最小可验证步骤（README 或脚本）。
