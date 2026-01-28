# 技术设计：移除微信小程序端（miniprogram）

## 技术方案
- 使用 Git 删除 `miniprogram/` 目录（包含已跟踪文件），并清理可能存在的未跟踪残留文件。
- 同步更新知识库：
  - 移除 `helloagents/wiki/overview.md` 中的 `miniprogram` 模块条目。
  - 移除 `helloagents/wiki/api.md` 中的小程序端说明。
  - 删除 `helloagents/wiki/modules/miniprogram.md` 模块文档。
  - 更新 `helloagents/CHANGELOG.md` 记录移除。
- 调整根目录 `.gitignore`，移除仅对小程序端有效的忽略项（避免误导）。

## 兼容性说明
- 不引入后端接口变更，不调整数据库结构，不影响现有 CI（后端仅执行 `mvn test`）。
- 若未来重新引入小程序端，需重新评估目录结构与文档索引。

## 安全与性能
- 仅涉及代码与文档清理，不涉及密钥、权限、生产环境操作。

## 测试与验证
- 执行 `mvn test`，确保后端测试通过。
- 检查仓库内是否仍存在对 `miniprogram` 的非历史引用（知识库应已清理）。

