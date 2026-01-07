# 任务清单: 待提交代码质量审查与收敛

目录: `helloagents/history/2026-01/202601072156_precommit_quality_review/`

---

## 0. 准备与范围确认
- [√] 0.1 生成待提交清单（已修改/未跟踪/大文件），按“必须提交/可选/禁止提交”三类标注，验证 why.md#核心场景-场景-误入文件与提交范围收敛
  > 备注: 当前未跟踪文件（排除 ignore 后）约 127 个；单次提交建议按“提交卫生/配置/核心逻辑/文档”分批。
- [√] 0.2 明确 `miniprogram/miniprogram/**` 是否为模板误入：若是，制定移除/忽略策略；若不是，补充来源与用途说明，验证 why.md#核心场景-场景-误入文件与提交范围收敛
  > 备注: 判定为模板/示例工程目录，已通过 `.gitignore` 忽略，避免误提交与仓库膨胀。

## 1. 提交卫生（优先级 P0）
- [√] 1.1 将本地私有文件加入忽略（例如 `miniprogram/project.private.config.json` 等），并确认不会影响团队协作，验证 why.md#核心场景-场景-误入文件与提交范围收敛
- [√] 1.2 清理/移除明显调试代码输出：
  - 小程序：移除或用开关保护 `console.log`
  - 脚本：将 `System.out.println` 改为可控日志或仅保留在测试脚本中明确标注
  验证 why.md#核心场景-场景-构建与测试可验证
  > 备注: 已移除 `miniprogram/pages/index/index.js` 的 `console.log`；脚本输出作为 smoke-test 结果展示暂保留。
- [√] 1.3 引入 `.gitattributes` 统一换行策略（建议 text=auto eol=lf），并处理关键文档 BOM/混合换行，验证 why.md#核心场景-场景-构建与测试可验证
  > 备注: 已新增 `.gitattributes`；为避免引入大面积换行 diff，暂未执行全量 renormalize 与 BOM 批量清理。

## 2. 配置与环境隔离（优先级 P0）
- [√] 2.1 调整 `src/main/resources/application.yml` 的默认 logging level：生产默认不启 debug；dev 通过 profile/示例文件开启，验证 why.md#核心场景-场景-配置与环境隔离
  > 备注: 已将 SQL stdout 与 MyBatis debug 下沉到 `application-dev.yml`；并移除 `application.yml` 中的明文数据库密码，改为环境变量注入。
- [√] 2.2 小程序端 `miniprogram/config.ts` 增加环境切换能力（dev/prod），避免硬编码 `127.0.0.1`，验证 why.md#核心场景-场景-配置与环境隔离
  > 备注: 已支持 `envVersion` + 本地覆盖（`im:httpBase` / `im:wsUrl`），release 可替换为线上域名。

## 3. 关键链路异常处理收敛（优先级 P0）
- [√] 3.1 逐个处理“空 catch/忽略异常”（优先鉴权/WS/缓存）：
  - 能忽略：加明确注释 + 最小日志/指标
  - 不能忽略：返回明确错误（HTTP/WS error）或 fail-fast
  验证 why.md#核心场景-场景-关键链路异常处理与可观测性
  > 备注: 已对关键路径的“吞异常”补齐最小 debug 日志/注释（鉴权拦截器/WS 幂等清理/cluster kick/解析兜底等）。
- [√] 3.2 统一错误码与异常映射（如 `GlobalExceptionHandler` 已覆盖 `NoResourceFoundException` 等），并确认不会把真实问题吞掉，验证 why.md#核心场景-场景-关键链路异常处理与可观测性

## 4. 功能一致性与回归（优先级 P1）
- [√] 4.1 验证鉴权链路：`/auth/login`、`/auth/refresh`、`sessionVersion` 失效策略；补齐必要测试或修复边界，验证 why.md#核心场景-场景-构建与测试可验证
  > 备注: 已通过 `mvn test`（含鉴权/配置/WS 相关单测）；未执行真实端到端登录链路联调。
- [-] 4.2 验证 WS：握手 query token、连接后 `AUTH/REAUTH`、断线重连、幂等 clientMsgId、群聊 fanout/cluster；必要时更新 smoke-test 场景，验证 why.md#核心场景-场景-构建与测试可验证
  > 备注: 需依赖服务端启动与多实例环境，当前未在本次执行中跑 smoke-test；建议后续按 scripts 目录脚本执行回归。

## 5. 构建与质量门禁（优先级 P1）
- [√] 5.1 后端：`mvn test` +（可选）`mvn -DskipTests=false package`；处理 Mockito 动态 agent 警告（可记录为技术债），验证 why.md#核心场景-场景-构建与测试可验证
  > 备注: `mvn test` 通过；Mockito 动态 agent 警告已记录为后续技术债（不影响当前构建结果）。
- [√] 5.2 前端：运行 `frontend` 的 lint/build（如有脚本），修复构建告警/类型问题，验证 why.md#核心场景-场景-构建与测试可验证
  > 备注: `npm run build` 通过。

## 6. 知识库同步（优先级 P2）
- [-] 6.1 修复知识库文档编码/显示异常（若存在），并补充“本次收敛策略/提交规范/环境配置说明”，验证 why.md#核心场景-需求-提交前质量把关
  > 备注: 发现 BOM/行尾差异风险，但为避免引入大面积文档 diff，本次未批量处理；建议后续单独开提交统一文档编码/行尾。

---

## 执行总结（本方案包）

### 提交范围建议
- **禁止提交:**
  - `miniprogram/project.private.config.json`（本地私有配置）
  - `miniprogram/miniprogram/**`（模板/示例目录）
- **建议分批:**
  1) 提交卫生（`.gitignore`/`.gitattributes`/调试输出清理）
  2) 配置收敛（`application.yml` → env + `application-dev.yml`）
  3) 关键链路健壮性（异常处理与可观测性）
  4) 文档/知识库（编码与内容一致性）

### 已执行验证
- `mvn test`
- `npm run build`（`frontend/`）
