# Changelog

本文件记录项目所有重要变更。
格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 变更
- CI: 新增 GitHub Actions（Java 17 + Maven test）
- 分支保护: master 启用必需PR审核与状态检查

### 新增
- 单聊（WS）：SAVED 落库确认、ACK_RECEIVED 接收确认、定时补发与离线标记（实现细节以代码为准）
- 鉴权（WS）：新增 REAUTH（续期），允许在连接不断开的情况下刷新 accessToken 过期时间

## [0.0.1-SNAPSHOT] - 2025-12-25

### 新增
- 初始化 helloagents 知识库骨架与基础文档

