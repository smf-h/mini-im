# 架构设计

## 总体架构
```mermaid
flowchart LR
    Client[IM客户端] -->|HTTP| Auth[AuthController]
    Client -->|HTTP| Query[列表/历史查询接口(规划)]
    Client -->|WebSocket| Ws[NettyWsServer]

    Auth --> Domain[Domain Services]
    Query --> Domain
    Ws --> Domain

    Domain --> MySQL[(MySQL)]
    Domain --> Redis[(Redis)]
```

## 技术栈
- **后端:** Spring Boot（Java 17）
- **网关:** Netty WebSocket（gateway 模块）
- **数据:** MyBatis-Plus + MySQL
- **缓存/会话:** Redis（结合 Caffeine 依赖用于本地缓存场景）
- **认证:** JWT（jjwt）

## 核心模块关系（以代码结构为准）
- `com.miniim.auth`: HTTP 鉴权与 token 管理
- `com.miniim.gateway`: WebSocket 接入、握手鉴权、会话注册、消息封装、定时补发兜底
- `com.miniim.domain`: 领域实体/Mapper/Service（会话、群组、消息、ack 等）
- `com.miniim.common`: 通用返回体与异常处理
- `com.miniim.config`: MyBatis-Plus 与线程池等基础配置

## 通信边界约定（v1）
- **WebSocket（主链路）**：单聊/群聊消息、好友申请、业务 ACK（落库确认/接收确认）、心跳。
- **HTTP（展示/查询）**：会话列表、好友/申请列表、历史消息分页（cursor）、基础管理类接口。

## 投递可靠性（v1 口径，以代码为准）
- 发送方：以 `ACK(SAVED)` 作为“服务端落库确认”；未收到则基于 `clientMsgId` 重发（幂等）。
- 接收方：收到消息后回 `ACK(ackType=delivered/read, serverMsgId=...)` 推进成员游标（cursor，SSOT 在 DB）。
  - 兼容：`ack_read` 视为 read。
- 离线补发/兜底补发：服务端按 `last_delivered_msg_id` 拉取未投递区间并补发；兜底定时补发需显式开启 `im.cron.resend.enabled=true`（默认关闭）。
