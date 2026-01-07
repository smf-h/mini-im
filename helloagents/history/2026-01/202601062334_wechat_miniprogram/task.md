# 任务清单: 微信小程序端（全功能，单页）

目录: `helloagents/plan/202601062334_wechat_miniprogram/`

---

## 1. 工程脚手架
- [√] 1.1 新建 `miniprogram/` 工程骨架（`app.json/app.ts`、`pages/index/index.*`、基础样式），验证 why.md#需求-登录与会话保持-场景-登录成功并进入主界面
- [√] 1.2 新增小程序配置与本地开发默认值（HTTP_BASE/WS_URL），验证 why.md#需求-websocket-连接与鉴权-场景-连接成功后可收发消息

## 2. 鉴权与 HTTP 客户端
- [√] 2.1 实现 token 存储与恢复（`wx.setStorageSync/getStorageSync`），验证 why.md#需求-登录与会话保持-场景-登录成功并进入主界面
- [√] 2.2 实现 `wx.request` 封装：自动加 Authorization、解包 `Result<T>`、401/40100 refresh 重试一次，验证 why.md#需求-登录与会话保持-场景-token-过期自动续期
- [√] 2.3 实现登录 UI（账号/密码）并接入 `POST /auth/login`，验证 why.md#需求-登录与会话保持-场景-登录成功并进入主界面

## 3. WebSocket 客户端
- [√] 3.1 实现 `wx.connectSocket` 封装：握手 `?token=` + 连接后发送 `AUTH`，等待 `AUTH_OK`，验证 why.md#需求-websocket-连接与鉴权-场景-连接成功后可收发消息
- [√] 3.2 实现重连退避、`token_expired` 自动 refresh+重连、`session_invalid/kicked` 退出登录，验证 why.md#需求-websocket-连接与鉴权-场景-被踢-会话失效

## 4. IM 功能（单聊/群聊/好友/群）
- [√] 4.1 会话列表（单聊/群聊）与基础资料拉取（HTTP cursor/list），验证 why.md#需求-单聊-场景-发送成功与失败重试
- [√] 4.2 单聊：发送 `SINGLE_CHAT`（含 `clientMsgId`）并处理 `ACK(SAVED)`，接收 `SINGLE_CHAT` 展示并回 `ACK`，验证 why.md#需求-单聊-场景-发送成功与失败重试
- [√] 4.3 群聊：发送 `GROUP_CHAT` 并展示接收的消息体；兼容 `GROUP_NOTIFY` 并用 HTTP `/group/message/since` 增量拉取，验证 why.md#需求-群聊-场景-group_notify-模式增量拉取
- [?] 4.4 好友/群相关：好友关系列表、好友申请列表/处理、群资料/成员管理（按现有 API），验证 why.md#需求-登录与会话保持-场景-登录成功并进入主界面（联调覆盖）
  > 备注: 已实现好友关系列表、好友申请列表/同意/拒绝、按码发起好友申请；已实现创建群与按码申请入群。群资料/成员管理尚未在小程序端补齐（可后续迭代）。

## 5. 朋友圈（Moments）
- [√] 5.1 朋友圈单页视图：feed/me、发动态、点赞/评论/删除，验证 why.md#需求-朋友圈-mvp-场景-发布动态并出现在时间线

## 6. 通话（信令）
- [√] 6.1 通话信令 UI 与状态机：处理 `CALL_*`（invite/accept/reject/cancel/end/ice/timeout）并可调用通话记录 HTTP（如需要），验证 why.md#需求-通话-phase1-信令-场景-发起通话邀请并处理对方接受-拒绝
  > 备注: 已实现 invite/accept/reject/cancel/end/ice 的交互与事件日志；timeout/异常按事件展示并清理状态（最小可用）。

## 7. 安全检查
- [√] 7.1 执行安全检查（按G9：输入校验、token 存储与日志脱敏、权限边界、重连风暴规避）
  > 备注: 小程序端未输出 accessToken/refreshToken；WS/HTTP 均复用现有鉴权；重连采用指数退避；入参做空值校验与基础约束。

## 8. 文档更新
- [√] 8.1 更新 `helloagents/wiki/overview.md` 模块索引：新增 `miniprogram`，并新增 `helloagents/wiki/modules/miniprogram.md`
- [√] 8.2 更新 `helloagents/wiki/api.md`：补充小程序端约定（HTTP_BASE/WS_URL、鉴权/重连策略、ID 字段字符串约定复述）
- [√] 8.3 更新 `helloagents/CHANGELOG.md`：记录新增小程序端

## 9. 联调验收
- [?] 9.1 冒烟联调：登录→WS 在线→单聊→群聊（含 notify 拉取）→朋友圈→通话信令（手工），记录问题清单并修复
  > 备注: 需在微信开发者工具导入 `miniprogram/` 并连接本地后端手工验证。
