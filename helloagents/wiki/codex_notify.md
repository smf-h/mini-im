# Codex 全局完成弹窗（Windows）

本项目环境支持为 Codex CLI 配置 **notify hook**：每次 `codex` 一次运行结束后，自动弹出系统提示框。

## 已启用方式（当前推荐）

- 脚本：`C:/Users/yejunbo/.codex/notify.py`
- 配置：`C:/Users/yejunbo/.codex/config.toml`
  - `notify = ["python", "C:/Users/yejunbo/.codex/notify.py"]`

脚本会监听事件类型 `agent-turn-complete`（以及少量失败事件），并在 hook 进程内同步弹窗（最可靠）：**会阻塞到你点 OK 为止**。

如需“后台弹窗不阻塞”（兼容性取决于你的终端/Job 管理方式），可设置环境变量 `CODEX_NOTIFY_ASYNC=1`。

## 禁用/调整

- 临时禁用：运行前设置环境变量 `CODEX_NOTIFY_DISABLED=1`
- 永久禁用：从 `C:/Users/yejunbo/.codex/config.toml` 删除 `notify = ...` 行
- 自定义标题：设置环境变量 `CODEX_NOTIFY_TITLE`（默认 `Codex`）
- 调试日志：设置环境变量 `CODEX_NOTIFY_DEBUG=1`，日志写入 `C:/Users/yejunbo/.codex/log/notify-hook.log`

## 手动触发（排障）

PowerShell 建议用“写临时 JSON 文件再传路径”，避免命令行引号转义问题：

```powershell
$p = Join-Path $env:TEMP "codex_notify_test.json"
'{"type":"agent-turn-complete","status":"done"}' | Set-Content -Encoding UTF8 $p
python C:\Users\yejunbo\.codex\notify.py $p
```
