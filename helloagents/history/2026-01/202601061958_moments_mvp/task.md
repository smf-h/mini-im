# 任务清单: 朋友圈（MVP）

目录: `helloagents/plan/202601061958_moments_mvp/`

---

## 1. 数据库与实体
- [√] 1.1 新增 Flyway migration `src/main/resources/db/migration/V6__moments_mvp.sql`，创建 `t_moment_post/t_moment_like/t_moment_comment`，验证 why.md#需求-发布动态-场景-发布成功
- [√] 1.2 新增实体与 Mapper（`com.miniim.domain.entity`/`com.miniim.domain.mapper`），验证 why.md#需求-浏览时间线仅好友-场景-拉取首页，依赖任务1.1

## 2. 朋友圈服务层
- [√] 2.1 新增 `MomentVisibilityService`：判定 viewer 是否可见 author 的动态（自见+好友），验证 why.md#需求-浏览时间线仅好友-场景-拉取首页，依赖任务1.2
- [√] 2.2 新增 `MomentPostService`：发布/删除动态（含 `ForbiddenWordFilter`、≤500字、删除权限），验证 why.md#需求-发布动态-场景-文本含违禁词、why.md#需求-删除自己动态-场景-删除动态，依赖任务2.1
- [√] 2.3 新增 `MomentLikeService`：点赞 toggle（唯一键冲突回读、更新计数），验证 why.md#需求-点赞可取消-场景-点赞取消赞，依赖任务2.2
- [√] 2.4 新增 `MomentCommentService`：评论发布/删除（一级评论、可删自己评论、楼主可删他人评论），验证 why.md#需求-评论一级-场景-删除评论，依赖任务2.2
- [√] 2.5 新增 `MomentFeedService`：时间线 cursor 查询（好友+自己，id 倒序游标），验证 why.md#需求-浏览时间线仅好友-场景-游标分页，依赖任务2.1

## 3. HTTP API
- [√] 3.1 新增 Controller `com.miniim.domain.controller.MomentController`（create/delete/feed/user/like/comment），所有接口强制登录并返回 `Result`，验证 why.md 各场景，依赖任务2.2-2.5
- [√] 3.2 补充 API 文档 `helloagents/wiki/api.md`（新增朋友圈接口列表），依赖任务3.1

## 4. 前端（MVP）
- [√] 4.1 新增朋友圈页面（发动态、列表、点赞/评论/删除自己动态），路由挂到主布局导航，验证 why.md 全链路（手工联调），依赖任务3.1

## 5. 安全检查
- [√] 5.1 执行安全检查（输入校验、权限控制、内容过滤、避免越权读/写），依赖任务3.1

## 6. 文档与一致性
- [√] 6.1 更新数据模型文档 `helloagents/wiki/data.md`（新增三张表与索引说明），依赖任务1.1
- [√] 6.2 新增模块文档 `helloagents/wiki/modules/moments.md` 并更新 `helloagents/wiki/overview.md` 模块索引，依赖任务3.1
- [√] 6.3 更新 `helloagents/CHANGELOG.md`，记录新增功能，依赖任务6.1-6.2

## 7. 测试
- [√] 7.1 新增 Service 单测：可见性（`MomentVisibilityService`），其余场景使用脚本冒烟覆盖，依赖任务2.1-2.5
- [√] 7.2 冒烟：新增 `scripts/moments-smoke-test/run.ps1` 并串到多实例 `auto-run.ps1`，覆盖“发动态/拉取/点赞/评论/删除”，依赖任务4.1
