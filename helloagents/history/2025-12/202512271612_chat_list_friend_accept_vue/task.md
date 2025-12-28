# 任务清单

方案包：202512271612_chat_list_friend_accept_vue  
范围：单聊会话列表（含最后消息）+ 好友申请同意建单聊 + Vue 用户站点 + 修复好友测试  
状态：进行中

## 任务列表

- [ ] HTTP：新增单聊会话列表（cursor/list，按更新时间排序，含最后一条消息）
- [ ] WS：单聊发送落库后 touch 更新 `t_single_chat.updated_at`
- [ ] HTTP：好友申请 accept/reject；accept 事务内创建 `t_friend_relation` + `t_single_chat`
- [ ] 测试：修复 ws-smoke-test 的 friend_request 断言误判
- [ ] 前端：新增 Vue 网站（登录/会话列表/聊天/好友申请/发起申请）
- [ ] 知识库：更新 `helloagents/wiki/api.md` 与 `helloagents/wiki/testing.md`
- [ ] 自测：`mvn -DskipTests package`

## 验收标准

- 会话列表按更新时间倒序，展示 lastMessage 与 updatedAt
- 好友申请同意后：双方成为好友且能在会话列表看到新会话（无消息时 lastMessage 为空）
- 前端可完成：登录 -> 发起好友申请 -> 对方同意 -> 进入聊天收发

## Task 变动记录

（如实现与预期不同，在此追加记录）

