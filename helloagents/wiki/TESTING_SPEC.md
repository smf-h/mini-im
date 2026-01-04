# Mini-IM 测试规范

本文档定义了 Mini-IM 项目的测试策略、工具栈及执行规范，旨在保证代码质量、系统稳定性及可维护性。

## 1. 测试分层策略

我们采用经典的测试金字塔模型：

*   **L1 单元测试 (Unit Tests)**: 占比 70%。关注单个类、方法或函数的逻辑。运行速度快，无外部依赖（Mock 数据库、网络）。
*   **L2 集成测试 (Integration Tests)**: 占比 20%。关注组件间的交互（如 Service + Repository + DB，Controller + Service）。
*   **L3 端到端/冒烟测试 (E2E/Smoke Tests)**: 占比 10%。关注整个系统的关键链路（如 WebSocket 消息收发全流程）。

## 2. 后端测试规范 (Java)

### 2.1 技术栈
*   **框架**: JUnit 5
*   **Mock 工具**: Mockito
*   **断言**: AssertJ (推荐) 或 JUnit 自带断言
*   **Spring 集成**: `spring-boot-starter-test`

### 2.2 命名规范
*   测试类名：`TargetClassTest` (例如 `UserServiceTest`)
*   测试方法名：`methodName_ShouldExpectedBehavior_WhenCondition`
    *   例：`register_ShouldThrowException_WhenUserExists()`
    *   例：`sendMessage_ShouldReturnAck_WhenValid()`

### 2.3 各层测试重点

#### Domain/Service 层 (重点)
*   **类型**: 纯单元测试 (Unit Test)
*   **规范**:
    *   使用 `@ExtendWith(MockitoExtension.class)`。
    *   Mock 所有依赖的 Repository 和其他 Service。
    *   **必须覆盖**: 所有业务逻辑分支、异常处理、边界条件。
    *   **禁止**: 启动 Spring Context (速度慢)。

```java
@ExtendWith(MockitoExtension.class)
class FriendRequestServiceTest {
    @Mock private FriendRequestMapper mapper;
    @InjectMocks private FriendRequestServiceImpl service;

    @Test
    void sendRequest_ShouldSave_WhenValid() {
        // Arrange
        when(mapper.selectCount(any())).thenReturn(0L);
        // Act
        service.sendRequest(1L, 2L, "hello");
        // Assert
        verify(mapper).insert(any());
    }
}
```

#### Repository/Mapper 层
*   **类型**: 集成测试 (Integration Test)
*   **规范**:
    *   使用 `@MybatisTest` 或 `@SpringBootTest`。
    *   使用 H2 内存数据库或 Testcontainers (Docker MySQL)。
    *   **重点**: 复杂的 SQL 查询、自定义 Mapper 方法。简单的 CRUD 可跳过。

#### Controller/Gateway 层
*   **类型**: 接口测试 (Slice Test)
*   **规范**:
    *   使用 `@WebMvcTest` (HTTP) 或针对 Netty Handler 的单元测试。
    *   Mock Service 层。
    *   **重点**: 参数校验、HTTP 状态码、JSON 序列化、WebSocket 协议解析。

### 2.4 WebSocket 专项测试
*   由于 Netty Handler 逻辑复杂，建议将业务逻辑剥离到 Service/Component 中单独测试。
*   Handler 本身使用 `EmbeddedChannel` 进行单元测试。
*   全链路使用 `scripts/ws-smoke-test` 进行验证。

## 3. 前端测试规范 (Vue 3 + TS)

### 3.1 技术栈
*   **框架**: Vitest (兼容 Jest API，速度更快，Vite 原生支持)
*   **组件测试**: Vue Test Utils
*   **断言**: Vitest 自带

### 3.2 测试重点

#### Utils/Helpers (纯函数)
*   **类型**: 单元测试
*   **重点**: `format.ts`, 数据转换逻辑。
*   **要求**: 100% 覆盖率。

#### Stores (Pinia)
*   **类型**: 单元测试
*   **重点**: Action 的状态变更逻辑。Mock API 调用。

#### Components (Vue 组件)
*   **类型**: 组件测试
*   **重点**:
    *   Props 传递是否正确渲染。
    *   Events 是否正确触发。
    *   **不建议**: 测试样式细节、第三方库内部逻辑。

## 4. 自动化冒烟测试 (Smoke Testing)

项目包含一套基于 Java/PowerShell 的 WebSocket 冒烟测试脚本，位于 `scripts/ws-smoke-test/`。

### 4.1 适用场景
*   后端核心逻辑修改后（如 Netty Handler, 鉴权, 消息路由）。
*   发布/部署前的最后一道防线。

### 4.2 运行方式
```powershell
# 运行所有场景
./scripts/ws-smoke-test/run.ps1

# 运行特定场景
./scripts/ws-smoke-test/run.ps1 -Scenario basic
```

### 4.3 扩展规范
*   新增场景需在 `WsSmokeTest.java` 中添加新的 `runScenario_XXX` 方法。
*   保持脚本的幂等性（每次运行生成新的随机 ID 或清理数据）。

## 5. 测试数据管理

*   **单元测试**: 使用 Mock 对象，不依赖真实数据。
*   **集成测试**:
    *   方案 A (推荐): H2 内存数据库，启动快，数据隔离好。需维护 `schema.sql` 兼容性。
    *   方案 B: Testcontainers，启动 Docker MySQL，环境最真实，但速度稍慢。
*   **冒烟测试**: 连接开发/测试环境真实数据库，需注意数据清理或使用测试账号。

## 6. 提交与 CI 规范

*   **本地开发**: 提交代码前，必须通过所有单元测试 (`mvn test`).
*   **Pull Request**: CI 流水线应自动运行：
    1.  后端单元测试 + 集成测试。
    2.  前端构建检查 (`vue-tsc --noEmit`).
    3.  (可选) 前端单元测试。
*   **覆盖率目标**:
    *   核心业务模块 (Service): > 80%
    *   整体项目: > 60%

## 7. 常见问题 (FAQ)

*   **Q: 为什么不测试 Getter/Setter?**
    *   A: 纯 POJO 的 Getter/Setter 没有逻辑，测试价值低，浪费时间。
*   **Q: 如何测试 WebSocket 的并发?**
    *   A: 单元测试难以模拟真实并发。建议使用 JMeter 或专门的压测工具（如 Gatling）进行性能/并发测试。
