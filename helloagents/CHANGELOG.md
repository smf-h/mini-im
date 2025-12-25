# Changelog

本文件记录项目所有重要变更。
格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 新增
- 单聊 v1：新增 REST 骨架（/api/single-chat），含发送/历史/会话接口（TEXT-only）
- 应用服务：SingleChatAppService（组合幂等与会话策略）
- 文档：wiki/api.md 增补单聊 REST


### 变更
- 分支保护: 临时关闭 Code Owner 审核和必需审核（单人开发），保留 build 状态检查

### 变更
- 代码所有者: 新增 .github/CODEOWNERS（默认 @smf-h）
- 审核策略: 启用 Code Owner 审核作为合并前置条件

### 变更
- CI: 新增 GitHub Actions（Java 17 + Maven test）
- 分支保护: master 启用必需PR审核与状态检查

## [0.0.1-SNAPSHOT] - 2025-12-25

### 新增
- 初始化 helloagents 知识库骨架与基础文档




