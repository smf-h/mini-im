# 技术设计: 单聊视频通话（WebRTC）

## 技术方案

### 核心技术
- WebRTC：`getUserMedia` / `RTCPeerConnection` / `RTCDataChannel`（本期不做）
- 信令：复用现有 Netty WebSocket（`WsFrameHandler` 转发）
- 穿透：Phase1 使用公共 STUN（无 TURN 兜底）
- 持久化：MySQL + Flyway 迁移（通话记录表）

### 方案对比（复杂任务）
- 方案1（推荐）：WS 作为信令转发 + 轻量服务端通话记录落库（通话状态主要由客户端事件驱动）
  - 优点：改动集中、实现快、符合现有架构（已有 WS、已有认证、已有 idempotency 思路）
  - 缺点：无 TURN 时网络适配弱；通话状态一致性依赖客户端上报
- 方案2（备选）：引入 Redis 通话状态（分布式仲裁）+ 服务端超时/忙线仲裁更强
  - 优点：多实例/断线重连更稳
  - 缺点：实现与测试成本更高，Phase1 不必要

本期采用方案1，并在协议与表结构上预留升级到方案2的扩展点（如 `callId`、状态机字段、失败原因）。

### 实现要点

#### 1) WebRTC 信令（WS）
在现有 `WsEnvelope` 上扩展通话信令字段，并新增以下 WS 事件类型（`type`）：
- `CALL_INVITE`：发起呼叫（带 `callId` + SDP offer）
- `CALL_RINGING`：服务端回给发起方“已投递来电”（或被叫在线确认）
- `CALL_ACCEPT`：被叫接听（带 SDP answer）
- `CALL_REJECT`：被叫拒绝
- `CALL_CANCEL`：呼叫方取消（在接听前）
- `CALL_END`：任意一方挂断/结束
- `CALL_ICE`：ICE candidate 交换
- `CALL_BUSY`：忙线
- `CALL_OFFLINE`：对方不在线
- `CALL_TIMEOUT`：超时未接

协议字段（建议新增到 `WsEnvelope`，避免复用 `body` 导致语义混淆）：
- `callId: string`（雪花 id，前端/服务端均按字符串处理）
- `callKind: string`（`video`，后续可扩展 `audio`）
- `sdp: string|null`（offer/answer）
- `iceCandidate: string|null`、`iceSdpMid: string|null`、`iceSdpMLineIndex: number|null`
- `callReason: string|null`（拒绝/失败原因）

路由规则：
- 所有通话信令都必须包含 `to`（对端 userId），服务端从已鉴权的 channel 绑定读取 `from`
- 服务端仅做转发与最小校验（在线、是否好友、是否忙线、callId 归属），不承载媒体数据

#### 2) 通话状态机（服务端最小化）
为 Phase1 提供“够用”的状态机以支持通话记录与未接：
- `RINGING`：已发起并投递（或等待投递）
- `ACCEPTED`：已接听
- `REJECTED`：被叫拒绝
- `CANCELED`：发起方取消
- `ENDED`：已结束（正常挂断）
- `MISSED`：超时未接
- `FAILED`：失败（离线/错误/权限/媒体设备失败等）

超时策略（Phase1）：
- 仅在服务端保存 `callId -> 过期时间` 的轻量内存/Redis TTL（方案1先用内存，后续可迁移 Redis）
- 超时到达后写 `MISSED` 并向双方推 `CALL_TIMEOUT`

#### 3) 通话记录（HTTP）
新增“通话记录”查询接口（游标/分页），前端可在设置页或单聊详情页展示。

#### 4) 前端 UI/交互
- 单聊页 `ChatView` 顶部新增“视频通话”按钮
- 全局通话弹窗：建议挂在 `AppLayout`，保证任意页面都能接到来电并操作
- 通话面板能力（Phase1）：
  - 本地预览 + 对端画面
  - 接听/拒绝/挂断
  - 静音（`audioTrack.enabled=false`）
  - 关摄像头（`videoTrack.enabled=false`）
- 失败处理：
  - 获取设备权限失败（无摄像头/拒绝授权）→ 显示错误并写 `FAILED`（reason=media_permission_denied 等）

## 架构设计
```mermaid
sequenceDiagram
  participant A as Caller(Web)
  participant WS as Gateway(WS)
  participant B as Callee(Web)
  participant DB as MySQL

  A->>WS: CALL_INVITE(callId, to, sdpOffer)
  WS->>DB: insert call_record(RINGING)
  WS->>B: CALL_INVITE(callId, from, sdpOffer)
  B->>WS: CALL_ACCEPT(callId, sdpAnswer)
  WS->>DB: update call_record(ACCEPTED)
  WS->>A: CALL_ACCEPT(callId, sdpAnswer)
  A<->>B: WebRTC media (SRTP)
  A->>WS: CALL_END(callId)
  WS->>DB: update call_record(ENDED, duration)
  WS->>B: CALL_END(callId)
```

## 架构决策 ADR
### ADR-001: 复用现有 WS 作为 WebRTC 信令（推荐）
**上下文:** 项目已经有 WS 网关、鉴权、路由与事件处理链路；新增媒体能力的关键是“信令转发”。
**决策:** 通话信令复用现有 WS 连接，在 `WsEnvelope.type` 新增 CALL_* 事件；媒体走 WebRTC P2P。
**理由:** 复用鉴权与连接管理、实现成本低、对服务端带宽压力最小。
**替代方案:** 引入独立信令服务/第三方媒体服务器 → 拒绝原因: Phase1 成本过高且不满足“先本地两窗口验收”的目标。
**影响:** 需要扩展 WS 协议与前端全局弹窗；生产环境仍需要 TURN（后续补）。

## API设计

### GET /call/record/cursor?limit=20&lastId=xxx
- **请求:** 游标分页；`lastId` 为空表示从最新开始
- **响应:** `[{ id, callId, peerUserId, direction, status, startedAt, acceptedAt, endedAt, durationSeconds, reason }]`

### GET /call/record/list?pageNo=1&pageSize=20
- **请求:** 普通分页
- **响应:** MyBatis-Plus Page

## 数据模型

建议新增通话记录表（仅元数据，不存 SDP/ICE，不存媒体内容）：
```sql
CREATE TABLE IF NOT EXISTS t_call_record (
  id BIGINT NOT NULL PRIMARY KEY,
  call_id BIGINT NOT NULL,
  single_chat_id BIGINT NULL,
  caller_user_id BIGINT NOT NULL,
  callee_user_id BIGINT NOT NULL,
  status TINYINT NOT NULL COMMENT '1=RINGING,2=ACCEPTED,3=REJECTED,4=CANCELED,5=ENDED,6=MISSED,7=FAILED',
  fail_reason VARCHAR(64) NULL,
  started_at DATETIME(3) NOT NULL,
  accepted_at DATETIME(3) NULL,
  ended_at DATETIME(3) NULL,
  duration_seconds INT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_call_record_call_id (call_id),
  KEY idx_call_record_caller_id (caller_user_id, id),
  KEY idx_call_record_callee_id (callee_user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 安全与性能
- **安全:**
  - 不在服务端日志中打印 SDP/ICE（可能包含内网地址等敏感信息）
  - 服务端校验：必须鉴权；必须是好友关系；`to` 只能是对端；callId 归属校验（防止伪造挂断/拒绝）
- **性能:**
  - 媒体不经过服务端，仅信令转发（低带宽）
  - 对每个用户限制并发通话数（busy）与信令频率（防刷）

## 测试与部署
- **测试:**
  - 本机同浏览器双窗口：呼叫→接听→双向音视频→挂断
  - 拒绝/忙线/超时：状态与记录正确
  - 静音/关摄像头：track enabled 切换生效
- **部署:**
  - Phase1：仅需后端升级 + 前端构建发布
  - 生产化：补充 TURN 配置并可通过 `application.yml` 下发 ICE servers

