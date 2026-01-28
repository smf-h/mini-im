# mini-im

> Spring Boot + Netty WebSocket + MyBatis-Plus 的 IM 服务端 + Vue3 联调前端。

## 目录结构速览

- `src/`：后端（Spring Boot）
- `frontend/`：前端（Vue3 + Vite）
- `scripts/`：压测/联调脚本（k6 + Java）
- `helloagents/wiki/`：项目文档（以代码为准同步更新）

## 快速开始（本地，首次启动可能稍久）

前置依赖：
- JDK 17
- Maven
- Node.js（建议 ≥ 18）
- MySQL 8.x（本地 `mini_im` 库）
- Redis（本地 `6379`）

### 1) 准备 MySQL / Redis

1. 启动 Redis（默认 `127.0.0.1:6379`）
2. 创建数据库（MySQL）：
   - 库名：`mini_im`
   - 表结构：由 Flyway 在启动时自动迁移创建（`src/main/resources/db/migration`）

### 2) 启动后端（HTTP + WS）

PowerShell（Windows）示例：
```powershell
cd .\mini-im

# 必填：MySQL 密码（默认用户名 root，可用 IM_MYSQL_USERNAME 覆盖）
$env:IM_MYSQL_PASSWORD = "你的MySQL密码"

# 必填：JWT 密钥（生产至少 32 字符；本地也建议改掉默认值）
$env:IM_AUTH_JWT_SECRET = "change-me-please-change-me-please-change-me"

mvn spring-boot:run
```

也可以先打包再启动：
```powershell
mvn -DskipTests package
java -jar \"target\\mini-im-0.0.1-SNAPSHOT.jar\"
```

启动后端口：
- HTTP：`http://127.0.0.1:8080`
- WS：`ws://127.0.0.1:9001/ws`

### 3) 启动前端（Vue3 + Vite）

```bash
cd frontend
npm install
npm run dev
```

浏览器打开 Vite 输出地址（默认 `http://127.0.0.1:5173`）。

## 演示/验收故事线（建议 5–10 分钟）

准备：
- 打开两个浏览器窗口（或一个正常窗口 + 一个无痕窗口）模拟两个用户：A / B
- 两边都打开前端（`http://127.0.0.1:5173`）

### Step 1：登录（首次登录自动注册）
- A：用任意用户名/密码登录（例如 `alice / 123456`）
- B：用任意用户名/密码登录（例如 `bob / 123456`）

期望：
- 都能进入站点（会话/通讯录/群组/朋友圈等）
- WS 自动连接成功（如断开可点页面上的“重连”）

### Step 2：加好友（FriendCode）
- B：进入“个人主页”，确认/复制自己的 `FriendCode`
- A：进入“通讯录”→ 添加 → 输入 `FriendCode` → 发送申请
- B：进入“好友申请”列表 → 接受

期望：
- A/B 都能看到这段单聊会话入口

### Step 3：单聊发送 + 已读回执（read）
- A：在单聊里发送 1–2 条消息
- B：进入该单聊并保持停留（触发已读推进）

期望：
- B 能实时收到消息
- A 能看到自己发出的消息从“未读”变为“已读”（对方阅读后）

### Step 4：断网/重连/离线补发（resend）
- 让 B 离线（任选一种）：
  - 直接关闭 B 的网页标签页
  - 或浏览器 DevTools 把 Network 设为 Offline
- A：继续发送 2 条消息
- B：恢复在线并重新打开页面/重新登录（或点“重连”）

期望：
- B 会在恢复在线后收到离线期间的消息（基于成员游标 cursor 的补发）

### Step 5：群聊 + @我（important）
- A：创建群（群组页）→ 打开群资料复制 `GroupCode`
- B：用 `GroupCode` 申请入群 → A 审批通过
- A：进入群聊，在输入框键入 `@` 选择 B 并发送

期望：
- B 在群列表看到 `@` 标记/重要提醒，进入群聊能看到被 @ 的消息

验收通过标准：
- 上述 5 个步骤都能跑通；如失败可按下方 FAQ 排查或查看日志/文档索引。

## 配置与端口速查

默认配置（以 `src/main/resources/application.yml` 为准）：
- HTTP：`8080`
- WS：`9001`（路径 `/ws`）
- MySQL：`127.0.0.1:3306/mini_im`
- Redis：`127.0.0.1:6379/0`

常用环境变量：
- `IM_MYSQL_USERNAME`（默认 `root`）
- `IM_MYSQL_PASSWORD`（默认空，建议显式设置）
- `IM_AUTH_JWT_SECRET`（默认有占位值，建议显式设置）
- `IM_REDIS_HOST` / `IM_REDIS_PORT` / `IM_REDIS_DATABASE`（默认 `127.0.0.1/6379/0`）

配置模板（便于容器化/部署时通过环境变量注入）：
- `src/main/resources/application.env.yml`
- `src/main/resources/application.env.values.yml`

## 文档索引（建议从这里开始）
- 项目概览：`helloagents/wiki/overview.md`
- API 手册：`helloagents/wiki/api.md`
- 数据模型：`helloagents/wiki/data.md`
- WS 投递 SSOT（一页纸）：`helloagents/wiki/ws_delivery_ssot_onepager.md`
- 测试与联调说明：`helloagents/wiki/testing.md`
- 前端说明：`frontend/README.md`

## 说明

- 本仓库当前不包含小程序端（已移除），联调以 `frontend/` 为准。

## 常见问题（FAQ）

1) 后端启动失败：MySQL 连接失败
- 检查 MySQL 是否启动、是否存在 `mini_im` 库
- 检查 `IM_MYSQL_USERNAME/IM_MYSQL_PASSWORD` 是否正确

2) 登录/refresh 报错：Redis 不可用
- refreshToken 依赖 Redis；请确认 Redis 已启动且可连通

3) 前端能打开但 WS 连不上
- 检查后端日志是否已启动 WS：`ws://127.0.0.1:9001/ws`
- 检查本机端口是否被占用（9001）

4) ID 精度问题（前端显示/比较异常）
- 所有语义为“ID”的 long/Long 字段，JSON 一律输出为字符串（详见 `helloagents/wiki/api.md` 开头说明）

## 录屏建议（可选但很加分）

录一个 60–90 秒小视频，按“验收故事线”的 Step 1→Step 4 快速演示：
- 两个窗口登录
- 加好友
- 单聊发送 → 已读变更
- B 断网/关闭 → 恢复后收到离线补发

你可以把视频放到：
- GitHub Release / issue / PR 描述
- 或在 README 里补充一个链接（如 B 站/网盘/私有盘）
