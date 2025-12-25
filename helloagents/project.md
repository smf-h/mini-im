# mini-im 项目技术约定

> 本文档描述当前仓库的技术栈与约定，作为后续变更的统一参考。

---

## 技术栈
- Java: 17
- Spring Boot: 3.4.0（parent）
- 构建: Maven（pom.xml）
- 数据访问: MyBatis-Plus
- 数据库: MySQL（schema: src/main/resources/db/schema-mysql.sql）
- 缓存/会话: Redis
- 网关: Netty WebSocket（gateway 模块）
- 认证: JWT（jjwt），Refresh Token（Redis/DB）

---

## 代码结构
- 入口类: src/main/java/com/miniim/ImApplication.java
- 包结构: com.miniim.{auth,gateway,domain,common,config,cache}

---

## 配置与资源
- 应用配置: src/main/resources/application.yml
- 网关配置: src/main/resources/application-gateway.yml
- 数据库初始化: src/main/resources/db/schema-mysql.sql

---

## 常用命令（本地）
- 构建: mvn -DskipTests package
- 运行: mvn spring-boot:run

> 当前仓库未包含 src/test/java 单元测试目录；如新增测试，请同步在知识库补充测试约定。