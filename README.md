# mini-im

一个面向学习与工程演进的 IM（即时通讯）项目：后端基于 Spring Boot + Netty WebSocket + MyBatis-Plus，配套 Vue3 前端用于联调与演示。

## 功能概览

- 账号体系：登录/鉴权（JWT accessToken + refreshToken）
- 单聊：落库 + 在线投递 + 已读推进（ACK delivered/read）+ 断线补发（resend）
- 群聊：群成员关系 + 群消息 + `@` 重要提醒（important）
- 网关能力：多实例路由、背压/慢消费者保护、基础限流
- 压测与联调：k6 脚本 + Java 压测脚本 + 多实例一键回归脚本

## 技术栈

- 后端：Spring Boot / Netty WebSocket / MyBatis-Plus / Flyway / Redis
- 前端：Vue3 + Vite + TypeScript
- 数据库：MySQL 8.x

## 项目结构

- `src/`：后端（Spring Boot）
- `frontend/`：前端（Vue3 + Vite）
- `scripts/`：压测/联调脚本（k6 + Java + 多实例回归）
- `helloagents/wiki/`：项目文档（以代码为准同步更新）

> 说明：仓库当前不包含小程序端（已移除）。

## 快速开始（本地）

前置依赖：JDK 17、Maven、Node.js（建议 ≥18）、MySQL 8、Redis。

1) 启动依赖
- Redis：默认 `127.0.0.1:6379`
- MySQL：创建库 `mini_im`（表结构由 Flyway 在启动时自动迁移创建：`src/main/resources/db/migration`）

2) 启动后端

必需环境变量：
- `IM_MYSQL_PASSWORD`
- `IM_AUTH_JWT_SECRET`（建议至少 32 字符）

Windows PowerShell：
```powershell
$env:IM_MYSQL_PASSWORD = "<your_mysql_password>"
$env:IM_AUTH_JWT_SECRET = "change-me-please-change-me-please-change-me"
mvn spring-boot:run
```

启动端口（默认配置见 `src/main/resources/application.yml`）：
- HTTP：`http://127.0.0.1:8080`
- WS：`ws://127.0.0.1:9001/ws`

3) 启动前端
```bash
cd frontend
npm install
npm run dev
```

## 文档与设计说明

- 项目概览：`helloagents/wiki/overview.md`
- API 手册：`helloagents/wiki/api.md`
- 数据模型：`helloagents/wiki/data.md`
- WS 投递 SSOT（一页纸）：`helloagents/wiki/ws_delivery_ssot_onepager.md`
- 测试/压测/联调：`helloagents/wiki/testing.md`
- 前端说明：`frontend/README.md`

## 常见问题（FAQ）

1) MySQL 连接失败
- 检查 MySQL 是否启动、是否存在 `mini_im` 库、`IM_MYSQL_USERNAME/IM_MYSQL_PASSWORD` 是否正确。

2) refreshToken/登录相关报错
- refreshToken 依赖 Redis；请确认 Redis 可用且地址配置正确（默认使用 `spring.data.redis.*`）。

3) 前端能打开但 WS 连不上
- 确认后端 WS 已监听：`ws://127.0.0.1:9001/ws`
- 确认 9001 端口未被占用。

4) 前端 ID 精度（长整型）
- 所有语义为 “ID” 的 long/Long 字段，JSON 会输出为字符串（详见 `helloagents/wiki/api.md`）。
