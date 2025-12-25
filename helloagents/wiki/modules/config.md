# 模块: config

## 职责
- 集中管理基础配置（MyBatis-Plus、线程池等）

## 关键实现（以代码为准）
- MyBatis-Plus 配置：`com.miniim.config.MybatisPlusConfig`
- 自动填充：`com.miniim.config.MyMetaObjectHandler`
- 线程池：`com.miniim.config.ImExecutorsConfig`