# 怎么做（高层设计）

1) 协议与会话
- WS：沿用 WsEnvelope（SINGLE_CHAT/ACK），客户端提供 clientMsgId，服务端生成 serverMsgId；
- 会话：按(user1Id,user2Id) 升序唯一，首条消息自动创建 t_single_chat 记录；
- 幂等：key=(fromUserId + '-' + clientMsgId)，首次保存并返回 SAVED；重复请求直接返回已占用的 serverMsgId。

2) REST API（仅 TEXT）
- POST /api/single-chat/send
  - body: { toUserId, content, clientMsgId }
  - auth: Bearer 必需；
  - resp: { serverMsgId, status: SAVED|DELIVERED, ts }
- GET /api/single-chat/history?peerId={id}&cursor={msgId?}&size={20}
  - cursor 语义：返回 id < cursor 的最近 size 条，按 id DESC；返回 nextCursor 和 hasMore；
- GET /api/single-chat/conversation?peerId={id}
  - 返回/创建单聊会话 id（t_single_chat.id）。

3) 服务层
- SingleChatAppService：
  - sendText(fromUserId, toUserId, clientMsgId, content) → 保存消息、尝试投递在线对端，更新状态为 DELIVERED；
  - history(peerId, cursor, size) → 计算singleChatId，按 id DESC 分页；

4) 鉴权与安全
- 复用 AccessTokenInterceptor；各接口强制登录（无 token → 401）。

5) 分支与合并策略
- 临时关闭“必需审核”，仅保留 CI build 状态检查；后续组队再恢复审核要求。
