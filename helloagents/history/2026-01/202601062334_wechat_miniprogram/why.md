# 变更提案: 微信小程序端（全功能，单页）

## 需求背景
当前仓库已具备完整的后端（HTTP + Netty WebSocket）与 Web 前端（`frontend/`），但缺少微信小程序端，导致移动端触达与使用门槛较高。新增小程序端可复用现有鉴权、会话/消息、好友/群、朋友圈、通话信令等能力，形成更贴近 IM 使用场景的客户端形态。

## 产品分析

### 目标用户与场景
- **用户群体:** 微信生态内的普通用户（无需安装 App）
- **使用场景:** 登录后即时聊天（单聊/群聊）、处理好友申请/群管理、浏览朋友圈互动、接收业务通知、发起/接收通话（信令）
- **核心痛点:** Web 端在移动端体验与入口不如小程序；且部分用户更偏好微信内使用

### 价值主张与成功指标
- **价值主张:** 以最小成本复用后端能力，提供“能用、顺滑、可靠”的小程序端
- **成功指标:**
  - 登录成功率、WS 连接成功率与重连成功率
  - 消息发送成功率（ACK(SAVED)）与接收确认（ACK）
  - 群聊在 `GROUP_NOTIFY` 模式下的增量拉取成功率
  - 朋友圈发布/浏览/点赞/评论链路成功率

### 人文关怀
- **隐私:** 朋友圈默认仅好友可见（后端已实现），小程序端不做“公开”入口
- **风控:** 文本输入由服务端 `ForbiddenWordFilter` 过滤；客户端仍做基础长度校验以减少无效请求
- **可达性:** 单页形态减少跳转；关键操作提供清晰反馈（发送中/失败/重试）

## 变更内容
1. 新增 `miniprogram/`：微信小程序工程（TypeScript），以“单页 + 多模块视图”承载全功能入口
2. 复用现有后端鉴权：HTTP `Authorization: Bearer <accessToken>`；WS 采用 `?token=<accessToken>` + 连接后 `AUTH` 帧
3. 覆盖功能：单聊、群聊（含 `GROUP_NOTIFY` 增量拉取）、好友/群管理、朋友圈、通话信令（仅信令/状态展示）

## 影响范围
- **模块:**
  - 新增：`miniprogram/`（微信小程序端）
- **文件:**
  - 新增小程序工程文件与页面/组件/服务封装
- **API:**
  - 复用现有 HTTP/WS 协议（以 `helloagents/wiki/api.md` 为准）
- **数据:**
  - 无新增表；完全复用现有数据模型

## 核心场景

### 需求: 登录与会话保持
**模块:** miniprogram
使用现有 `POST /auth/login` 登录；自动处理 `40100` 与 HTTP 401 的 refresh；持久化 token 并恢复登录态。

#### 场景: 登录成功并进入主界面
已输入账号密码，后端返回 token
- 保存 `userId/accessToken/refreshToken`
- 初始化 HTTP 与 WS 客户端

#### 场景: token 过期自动续期
已登录，后端返回 `40100` 或 HTTP 401
- 自动 `POST /auth/refresh` 换发 accessToken
- WS 断开后自动重连并重新 AUTH

### 需求: WebSocket 连接与鉴权
**模块:** miniprogram
按后端约定：握手 query `token`，连接后发送 `AUTH`，收到 `AUTH_OK` 视为可用。

#### 场景: 连接成功后可收发消息
网络正常，token 有效
- 收到 `AUTH_OK` 后进入在线状态

#### 场景: 被踢/会话失效
服务端返回 `AUTH_FAIL session_invalid` 或 `ERROR session_invalid/kicked`
- 清理本地 token 并回到登录态

### 需求: 单聊
**模块:** miniprogram
发送 `SINGLE_CHAT`（含 `clientMsgId`），收到 `ACK(SAVED)` 更新本地消息状态；接收方收到 `SINGLE_CHAT` 展示并可回 `ACK`。

#### 场景: 发送成功与失败重试
发送消息后等待 ACK
- 成功：标记已发送
- 失败：提示重试（保持同一 `clientMsgId`，满足幂等）

### 需求: 群聊
**模块:** miniprogram
发送 `GROUP_CHAT`；接收端可收到消息体或 `GROUP_NOTIFY` 后走 HTTP `/group/message/since` 拉取增量（按服务端策略）。

#### 场景: GROUP_NOTIFY 模式增量拉取
收到 `GROUP_NOTIFY`（含 `serverMsgId`）
- 拉取 `GET /group/message/since?groupId=...&sinceId=...` 并合并到本地消息流

### 需求: 朋友圈（MVP）
**模块:** miniprogram
复用 `/moment/*` 接口：发布/删除、时间线、点赞、评论。

#### 场景: 发布动态并出现在时间线
发布成功
- 本地插入新动态并刷新列表

### 需求: 通话（Phase1：信令）
**模块:** miniprogram
复用 WS `CALL_*` 信令与通话记录 HTTP，仅做信令收发与状态展示（不直接实现 RTC 媒体链路）。

#### 场景: 发起通话邀请并处理对方接受/拒绝
邀请成功后进入 ringing/connected 状态
- 根据 `CALL_*` 更新 UI 状态与计时

## 风险评估
- **风险:** 单页承载全功能，组件状态复杂，易产生性能与维护成本
- **缓解:** 模块化拆分（views/components/stores/services），统一状态管理；后续可平滑拆分为多 page（不破坏核心服务层）

