# 轻量迭代任务清单：通知增强 + 数据库自动迁移

目标：
- 通知（toast）更美观，支持点击跳转；
- 收到好友申请（`FRIEND_REQUEST`）也弹通知；
- 数据库结构变更可自动迁移（Flyway）。

任务：
- [√] 后端：接入 Flyway，新增初始化迁移脚本（V1），并开启 `baseline-on-migrate` 以兼容已有库
- [√] 前端：升级 toast 数据结构（title/kind/path），并将 toast 改为可点击样式
- [√] 前端：收到 `FRIEND_REQUEST` 时弹 toast，点击跳转到 `/friends`
- [√] 文档：同步更新 `helloagents/wiki/modules/frontend.md` 与 `helloagents/CHANGELOG.md`
- [√] 验证：`npm -C frontend run build` 与 `mvn test`
