# 技术设计: 微信小程序端（全功能，单页）

## 技术方案

### 核心技术
- 微信小程序原生框架（WXML/WXSS/JS）
- TypeScript（小程序工程 TS 支持）
- 自研轻量 UI 组件（尽量不引入大体量依赖；必要时再引入组件库）
- 复用后端协议：HTTP `Result<T>` + WS `WsEnvelope`

### 实现要点
- **工程结构（建议）：**
  - `miniprogram/app.*`：应用入口与全局初始化
  - `miniprogram/pages/index/index.*`：单页容器（内部切换模块视图：会话/联系人/朋友圈/我的/通话）
  - `miniprogram/services/http.ts`：`wx.request` 封装 + `Result` 解包 + 401/40100 自动 refresh
  - `miniprogram/services/ws.ts`：`wx.connectSocket` 封装 + query token + AUTH/REAUTH + 重连退避
  - `miniprogram/stores/*`：token、WS 状态、会话/消息缓存（按功能拆分）
  - `miniprogram/utils/*`：`clientMsgId`、时间格式、节流/去抖、ID 字符串处理
- **鉴权与 token：**
  - 登录：`POST /auth/login` → 存储 `userId/accessToken/refreshToken/expireAtMs`
  - HTTP：默认携带 `Authorization: Bearer <accessToken>`，遇 401 或业务 `code=40100` 自动 refresh 后重试一次
  - WS：握手 `?token=`，连接后发送 `AUTH`；收到 `AUTH_OK` 视为成功
  - 失效：`session_invalid/invalid_token/kicked` → 清理 token 并回登录态
- **消息与幂等：**
  - 发送消息必须生成并持久化 `clientMsgId`（重试保持不变）
  - 发送成功以 `ACK(SAVED)` 为准；接收端送达/已读按现有 `ACK` 协议推进
- **群聊策略兼容：**
  - 同时兼容服务端两种下发：`GROUP_CHAT`（消息体）与 `GROUP_NOTIFY`（通知 + HTTP 增量拉取）
  - 本地为每个群维护 `sinceId`（最新已知 `serverMsgId`），收到 notify 时按 since 拉取并合并
- **ID 精度：**
  - 小程序侧所有“语义为 ID”的字段按字符串处理（与 `helloagents/wiki/api.md` 一致）

## 架构决策 ADR

### ADR-001: 小程序技术栈选择（原生-推荐）
**上下文:** 需要在仓库内新增小程序端，覆盖全功能；同时希望依赖最少、落地最快、与后端协议贴合。
**决策:** 采用微信小程序原生工程 + TypeScript，自研轻量 UI 与服务封装。
**理由:** 不引入跨端框架的额外构建复杂度；更贴近 `wx.request/wx.connectSocket` 能力；更易与现有 WS 协议对齐并排查问题。
**替代方案:** uni-app（Vue）/Taro（React）→ 拒绝原因: 需要引入新的构建链与依赖管理，且与现有仓库前端构建体系并存时复杂度更高。
**影响:** UI 复用 Web 端代码的收益较低；但服务层抽象后仍可在未来迁移到跨端框架。

## API设计
不新增后端 API；完全复用现有接口与协议：
- HTTP：`/auth/*`、`/single-chat/*`、`/group/*`、`/friend/*`、`/moment/*`、`/call/*` 等（详见 `helloagents/wiki/api.md`）
- WS：`AUTH/REAUTH/PING`、`SINGLE_CHAT/GROUP_CHAT/GROUP_NOTIFY`、`ACK`、`FRIEND_REQUEST`、`CALL_*` 等（详见 `helloagents/wiki/api.md`）

## 安全与性能
- **安全:**
  - token 存储使用小程序存储（`wx.setStorageSync`），避免输出日志
  - 所有写操作做基本入参校验（空/长度/ID 合法性），避免无效请求与潜在注入
  - WS 收到 `session_invalid/kicked` 立即清理并退出登录态，避免死循环重连
- **性能:**
  - 单页长列表（会话/消息/动态）使用分页与虚拟化策略（先按 cursor 分页；必要时再做虚拟列表）
  - WS 重连指数退避，避免网络抖动导致风暴

## 测试与部署
- **测试:**
  - 小程序侧以“手工联调 + 关键路径日志（不含 token/SDP/ICE）”为主
  - 后端已有单测继续复用；必要时补充接口冒烟脚本（可选）
- **部署:**
  - 小程序工程可直接用微信开发者工具导入 `miniprogram/`
  - 通过配置文件/常量设置 `HTTP_BASE` 与 `WS_URL`，默认指向本地 `127.0.0.1`

