# 任务清单

方案包：202512271429_friend_request  
范围：好友申请（WS 落库 + 幂等 + best-effort 推送）+ HTTP 游标列表 + 前端联调页  
状态：进行中

## 任务列表

- [√] WS：新增 `FRIEND_REQUEST` 处理（校验、落库、ACK(saved)）
- [√] WS：幂等（Caffeine：`FRIEND_REQUEST:clientMsgId`）与错误处理（失败回滚幂等键）
- [√] WS：接收方在线时 best-effort 推送一次（不要求 ACK、不重试）
- [√] HTTP：实现 inbox/outbox/all 的 cursor 接口
- [√] HTTP：实现 inbox/outbox/all 的 list 接口（Page）
- [√] 前端：新增 `frontend/` 联调页面（login、ws、发申请、列表游标）
- [√] 后端：增加 CORS 配置（仅 localhost/127.0.0.1）
- [√] 知识库：更新 `helloagents/wiki/api.md` 与 `helloagents/wiki/data.md`
- [√] 自测：`mvn -DskipTests package`

## 验收标准

- 发送方未收到 `ACK(saved)` 重发时服务端不重复落库，并返回相同 `serverMsgId`
- 接收方在线时最多收到一次通知；离线不补发（本次不做可靠推送）
- inbox/outbox/all 均可游标翻页展示
- 前端页面可用于前后端联调（前后端分离）

## Task 变动记录

- 2025-12-27：HTTP 列表接口从 “/inbox|/outbox|/all” 三套路径，调整为统一接口：
  - `/friend/request/cursor?box=inbox|outbox|all`
  - `/friend/request/list?box=inbox|outbox|all`
  目的：减少重复 Controller/Service 代码，保持接口语义不变。
