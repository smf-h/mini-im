# 技术设计: 朋友圈（MVP）

## 技术方案

### 核心技术
- Spring Boot Web（HTTP Controller）
- MyBatis-Plus（实体/Mapper）
- MySQL（Flyway migration）
- 复用现有：`AuthContext`、`AccessTokenInterceptor`、`ForbiddenWordFilter`

### 实现要点
- **数据模型：**
  - `t_moment_post`：动态主表（作者、内容、删除标记、计数）
  - `t_moment_like`：点赞表（postId + userId 唯一）
  - `t_moment_comment`：评论表（一级评论；postId + id 游标分页）
- **可见性（仅好友）：**
  - 可见条件：`viewerId == authorId` 或 `viewerId` 与 `authorId` 存在好友关系（复用 `t_friend_relation`）
  - feed 查询：取 viewer 的好友 id 列表（含自己），按 `author_id in (...)` + `id < lastId` 分页
  - MVP 先走直查与安全上限（例如好友上限/分页上限）；后续再优化为“预聚合时间线”
- **一致性与并发：**
  - 点赞 toggle：优先使用唯一键约束避免重复；冲突时回读当前状态
  - 删除动态：仅作者可删；删除后前端不再展示；点赞/评论可选择级联清理或保留（MVP 推荐：软删 post，同时软删评论/点赞或级联物理删，二选一）
- **内容安全：**
  - 动态/评论内容写库前都经 `ForbiddenWordFilter.sanitize`
  - 字数限制：服务端强制（≤500）

## 架构决策 ADR

### ADR-001: 时间线查询策略（MVP 选直查）
**上下文:** 朋友圈时间线可以做“写扩散（预聚合）”或“读扩散（直查）”。MVP 需要最快落地、风险最低。
**决策:** MVP 采用“读扩散直查”：基于好友列表（含自己）过滤动态，按 `id` 倒序游标分页。
**理由:** 改动最小、易理解、易验证；在好友规模不大时性能可接受。
**替代方案:** 写扩散预聚合（t_moment_feed）→ 拒绝原因: 需要异步扇出与幂等/补偿，复杂度高，不适合 MVP。
**影响:** 好友规模大时可能慢；后续可以按数据量升级为写扩散或引入缓存/分片。

## API设计

### [POST] /moment/post/create
- **请求:** `{ "content": "..." }`
- **响应:** `{ "postId": "..." }`

### [POST] /moment/post/delete
- **请求:** `{ "postId": "..." }`
- **响应:** `Result.okVoid()`

### [GET] /moment/feed/cursor
- **请求参数:** `limit`（默认20，最大100）、`lastId`（可空）
- **响应:** `[{ post... }, ...]`（按 `id desc`）

### [GET] /moment/user/cursor
- **请求参数:** `userId`（作者）、`limit`、`lastId`
- **响应:** `[{ post... }, ...]`

### [POST] /moment/like/toggle
- **请求:** `{ "postId": "...", "action": "toggle" }`（或仅 postId）
- **响应:** `{ "liked": true|false, "likeCount": 12 }`

### [POST] /moment/comment/create
- **请求:** `{ "postId": "...", "content": "..." }`
- **响应:** `{ "commentId": "..." }`

### [POST] /moment/comment/delete
- **请求:** `{ "commentId": "..."}`
- **响应:** `Result.okVoid()`

### [GET] /moment/comment/cursor
- **请求参数:** `postId`、`limit`、`lastId`
- **响应:** `[{ comment... }, ...]`

## 数据模型

```sql
-- 设计草案（最终以 migration 为准）
CREATE TABLE t_moment_post (
  id BIGINT NOT NULL PRIMARY KEY,
  author_id BIGINT NOT NULL,
  content VARCHAR(512) NOT NULL,
  like_count INT NOT NULL DEFAULT 0,
  comment_count INT NOT NULL DEFAULT 0,
  deleted TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  KEY idx_mp_author_id_id (author_id, id),
  KEY idx_mp_id (id)
);

CREATE TABLE t_moment_like (
  id BIGINT NOT NULL PRIMARY KEY,
  post_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_ml_post_user (post_id, user_id),
  KEY idx_ml_user_id (user_id),
  KEY idx_ml_post_id (post_id)
);

CREATE TABLE t_moment_comment (
  id BIGINT NOT NULL PRIMARY KEY,
  post_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  content VARCHAR(512) NOT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  KEY idx_mc_post_id_id (post_id, id),
  KEY idx_mc_user_id (user_id)
);
```

## 安全与性能
- **安全:**
  - 所有接口必须登录（使用 `AuthContext.getUserId()` 校验）
  - 可见性校验：查询/点赞/评论前先校验“目标动态对我可见”
  - 输入校验：content 非空、长度≤500；避免 ID 注入（均为 long）
- **性能:**
  - feed 直查会有 `IN` 列表：MVP 先做 limit 上限、好友数量上限保护
  - 点赞/评论计数：可在事务内更新计数或异步修正（MVP 先同步更新，保证读一致）

## 测试与部署
- **测试:**
  - Service 单元测试：可见性判定、点赞 toggle、评论删除权限
  - 冒烟：前端页面基本联调（创建/浏览/点赞/评论/删除）
- **部署:**
  - 增加 Flyway migration（V6）后，启动自动迁移

