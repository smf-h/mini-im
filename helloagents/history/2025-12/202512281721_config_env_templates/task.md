# 轻量迭代任务清单：配置模板双文件

目标：
- 提供两份配置文件：一份仅包含参数变量；另一份给出对应的示例取值（不包含真实敏感信息）。

任务：
- [√] 新增 `src/main/resources/application.env.yml`（仅变量）
- [√] 新增 `src/main/resources/application.env.values.yml`（示例值）
- [√] 文档：更新 `helloagents/wiki/modules/config.md` 与 `helloagents/CHANGELOG.md`
- [√] 归档：迁移方案包至 `helloagents/history/` 并更新 `helloagents/history/index.md`
