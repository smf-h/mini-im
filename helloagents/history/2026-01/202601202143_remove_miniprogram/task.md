# 任务清单：移除微信小程序端（miniprogram）

目录: `helloagents/plan/202601202143_remove_miniprogram/`

---

## 1. 代码清理
- [√] 1.1 删除 `miniprogram/` 目录（含跟踪与非跟踪文件），确保仓库不再包含小程序端工程文件
- [√] 1.2 清理根目录 `.gitignore` 中与小程序端相关的忽略项，避免产生“残留模块仍存在”的误解

## 2. 知识库同步
- [√] 2.1 更新 `helloagents/wiki/overview.md`：移除 `miniprogram` 模块索引条目
- [√] 2.2 更新 `helloagents/wiki/api.md`：移除小程序端约定说明
- [√] 2.3 删除 `helloagents/wiki/modules/miniprogram.md` 模块文档
- [√] 2.4 更新 `helloagents/CHANGELOG.md`：记录本次移除

## 3. 安全检查
- [√] 3.1 确认本次变更不涉及敏感信息/生产环境操作（EHRB 规避）

## 4. 测试
- [√] 4.1 执行 `mvn test`，确保后端测试通过

## 5. 方案包迁移
- [√] 5.1 按 G11 将本方案包从 `helloagents/plan/` 迁移至 `helloagents/history/2026-01/`，并更新 `helloagents/history/index.md`
