# Web UI 微调与登录态回收（Task）

> 状态符号：
> - [ ] 待执行
> - [√] 已完成
> - [X] 执行失败
> - [-] 已跳过
> - [?] 待确认

## 任务
- [√] 好友申请页：将“全部”方向展示从文本箭头改为图标化表达，并统一非可操作状态为状态胶囊
- [√] 去调试信息：移除 `uid/群号` 等调试展示（设置页/侧边栏菜单/发起单聊弹窗/群资料/群成员抽屉）
- [√] 登录态回收：HTTP 鉴权失败（401/40100 且 refresh 失败）自动清理并跳转登录页
- [√] 构建验证：通过 `npm -C frontend run build`
- [√] 知识库同步：更新 `helloagents/CHANGELOG.md`、`helloagents/wiki/modules/frontend.md`、`helloagents/project.md`

