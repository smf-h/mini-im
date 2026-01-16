# 轻量迭代任务清单：Codex 全局完成弹窗

- [√] 新增脚本：`C:/Users/yejunbo/.codex/notify.py`（监听 Codex notify 事件，完成时弹窗）
- [√] 更新配置：`C:/Users/yejunbo/.codex/config.toml`（配置 `notify = [...]`）
- [√] 同步知识库：新增 `helloagents/wiki/codex_notify.md`（启用/禁用说明）
- [√] 记录变更：更新 `helloagents/CHANGELOG.md`
- [√] 验证：手动触发 notify 脚本（不阻塞 Codex 退出）
- [√] 迁移方案包：移动到 `helloagents/history/2026-01/` 并更新 `helloagents/history/index.md`
