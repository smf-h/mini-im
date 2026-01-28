# 轻量迭代：弃用 t_message_ack（仅保留表）+ 补充审计/统计口径

任务清单：
- [√] 确认代码未写入 `t_message_ack`（当前无 insert 调用点）
- [√] 更新“一页纸”：明确弃用 `t_message_ack` 写入，并补充审计排障/维度统计说明与替代方案
- [√] 更新数据模型与 domain 模块文档：标注 `t_message_ack` 弃用/保留（非 SSOT）
- [√] 更新 CHANGELOG：记录本次口径澄清
- [-] 验证：仅文档变更，跳过测试

