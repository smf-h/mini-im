# 模块: moments

## 职责
- 提供朋友圈（MVP）能力：发布动态、时间线浏览、点赞、评论、删除（软删）
- 可见性：仅“好友 + 自己”可见（不做陌生人公开）

## 关键实现（以代码为准）
- Controller：`com.miniim.domain.controller.MomentController`
- 可见性：`com.miniim.domain.service.MomentVisibilityService`（依赖好友集合）
- 发布/删除：`com.miniim.domain.service.MomentPostService`
- 点赞：`com.miniim.domain.service.MomentLikeService`（toggle；唯一键兜底）
- 评论：`com.miniim.domain.service.MomentCommentService`（一级评论；楼主可删他人评论）
- 时间线：`com.miniim.domain.service.MomentFeedService`（cursor：`id desc`）

## 数据模型（MVP）
- `t_moment_post`：动态主体（`author_id/content/like_count/comment_count/deleted`）
- `t_moment_like`：点赞（`uk(post_id,user_id)`，用于 toggle 去重）
- `t_moment_comment`：评论（软删；游标索引 `idx(post_id,id)`）

## HTTP API（摘要）
- `POST /moment/post/create`
- `POST /moment/post/delete`
- `GET /moment/feed/cursor`
- `GET /moment/user/cursor`
- `POST /moment/like/toggle`
- `POST /moment/comment/create`
- `POST /moment/comment/delete`
- `GET /moment/comment/cursor`

## 缓存依赖
- 时间线“好友集合”依赖：`com.miniim.domain.service.FriendRelationService#friendIdSet`
  - 缓存实现：`com.miniim.domain.cache.FriendIdsCache`
  - 写路径在同意好友申请后会主动失效（对双方）

