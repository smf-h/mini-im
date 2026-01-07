# mini-im

> 基于 Spring Boot + Netty WebSocket + MyBatis-Plus 的 IM 服务端（以当前代码为准）。

---

## 1. 项目概述

### 目标与范围
- **目标内:** 提供登录/鉴权能力，提供 WebSocket 网关能力，支撑消息与会话等领域模型。
- **目标外:** 生产化部署与运维体系（本仓库未体现）。

---

## 2. 模块索引

| 模块名称 | 职责 | 状态 | 文档 |
|---------|------|------|------|
| auth | 登录/鉴权/JWT 与 refresh token | 已实现 | modules/auth.md |
| gateway | Netty WebSocket 网关、会话管理、幂等处理 | 已实现 | modules/gateway.md |
| domain | 领域实体/枚举/Mapper/Service | 已实现 | modules/domain.md |
| common | 通用返回体/错误码/全局异常处理 | 已实现 | modules/common.md |
| config | MyBatis-Plus、线程池等基础配置 | 已实现 | modules/config.md |
| frontend | Vue3+TS 联调前端（登录/会话/聊天/好友申请/站内通知） | 已实现 | modules/frontend.md |
| cache | Redis cache-aside（用户/好友/群/会话等读优化） | 已实现 | modules/cache.md |
| moments | 朋友圈（MVP：动态/时间线/点赞/评论） | 已实现 | modules/moments.md |
| miniprogram | 微信小程序端（原生+TS，单页） | 已实现 | modules/miniprogram.md |

---

## 3. 快速链接
- 技术约定: ../project.md
- 架构设计: arch.md
- API 手册: api.md
- 数据模型: data.md
- 变更历史: ../history/index.md
