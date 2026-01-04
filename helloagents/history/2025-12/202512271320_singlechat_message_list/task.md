# 任务清单

方案包：202512271320_singlechat_message_list  
范围：单聊聊天内容 HTTP 查询（游标拉取 + 普通分页 list）  
状态：进行中

## 任务列表

- [√] 新增 HTTP 接口：单聊消息游标拉取（按 `id` 倒序，`id < lastId`）
- [√] 新增 HTTP 接口：单聊消息普通分页 list（pageNo/pageSize）
- [√] 增加访问控制：仅会话参与者可查询；会话不存在返回空
- [√] 更新知识库：`helloagents/wiki/api.md`、`helloagents/wiki/data.md`
- [√] 自测：`mvn -DskipTests package`

## 验收标准

- 支持以 `peerUserId` 查询单聊消息（无需客户端持有 `singleChatId`）
- cursor 接口支持向上翻页（lastId 游标）
- list 接口支持分页

## Task 变动记录

（如实现与预期不同，在此追加记录）
