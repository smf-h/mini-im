package com.miniim.domain.controller;

import com.miniim.auth.web.AuthContext;
import com.miniim.common.api.ApiCodes;
import com.miniim.common.api.Result;
import com.miniim.domain.entity.MomentCommentEntity;
import com.miniim.domain.entity.MomentPostEntity;
import com.miniim.domain.service.MomentCommentService;
import com.miniim.domain.service.MomentFeedService;
import com.miniim.domain.service.MomentLikeService;
import com.miniim.domain.service.MomentPostService;
import com.miniim.domain.service.MomentVisibilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/moment")
public class MomentController {

    private final MomentVisibilityService visibilityService;
    private final MomentPostService momentPostService;
    private final MomentLikeService momentLikeService;
    private final MomentCommentService momentCommentService;
    private final MomentFeedService momentFeedService;

    public MomentController(
            MomentVisibilityService visibilityService,
            MomentPostService momentPostService,
            MomentLikeService momentLikeService,
            MomentCommentService momentCommentService,
            MomentFeedService momentFeedService
    ) {
        this.visibilityService = visibilityService;
        this.momentPostService = momentPostService;
        this.momentLikeService = momentLikeService;
        this.momentCommentService = momentCommentService;
        this.momentFeedService = momentFeedService;
    }

    public record CreatePostRequest(String content) {
    }

    public record CreatePostResponse(Long postId) {
    }

    @PostMapping("/post/create")
    public Result<CreatePostResponse> createPost(@RequestBody CreatePostRequest req) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        String content = req == null ? null : req.content();
        long postId = momentPostService.create(userId, content);
        return Result.ok(new CreatePostResponse(postId));
    }

    public record DeletePostRequest(Long postId) {
    }

    @PostMapping("/post/delete")
    public Result<Void> deletePost(@RequestBody DeletePostRequest req) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        Long postId = req == null ? null : req.postId();
        if (postId == null || postId <= 0) {
            return Result.fail(ApiCodes.BAD_REQUEST, "bad_post_id");
        }

        MomentPostEntity post = momentPostService.getById(postId);
        if (post == null || (post.getDeleted() != null && post.getDeleted() != 0)) {
            return Result.fail(ApiCodes.NOT_FOUND, "not_found");
        }
        if (post.getAuthorId() == null || !post.getAuthorId().equals(userId)) {
            return Result.fail(ApiCodes.FORBIDDEN, "forbidden");
        }

        momentPostService.softDelete(postId, userId);
        return Result.okVoid();
    }

    @GetMapping("/feed/cursor")
    public Result<List<MomentFeedService.PostDto>> feedCursor(
            @RequestParam(defaultValue = "20") Long limit,
            @RequestParam(required = false) Long lastId
    ) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        return Result.ok(momentFeedService.feedCursor(userId, limit, lastId));
    }

    @GetMapping("/user/cursor")
    public Result<List<MomentFeedService.PostDto>> userCursor(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "20") Long limit,
            @RequestParam(required = false) Long lastId
    ) {
        Long viewerId = AuthContext.getUserId();
        if (viewerId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        if (userId == null || userId <= 0) {
            return Result.fail(ApiCodes.BAD_REQUEST, "bad_user_id");
        }
        if (!visibilityService.canViewAuthor(viewerId, userId)) {
            return Result.fail(ApiCodes.FORBIDDEN, "forbidden");
        }
        return Result.ok(momentFeedService.userCursor(viewerId, userId, limit, lastId));
    }

    public record ToggleLikeRequest(Long postId) {
    }

    public record ToggleLikeResponse(Boolean liked, Integer likeCount) {
    }

    @PostMapping("/like/toggle")
    public Result<ToggleLikeResponse> toggleLike(@RequestBody ToggleLikeRequest req) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        Long postId = req == null ? null : req.postId();
        if (postId == null || postId <= 0) {
            return Result.fail(ApiCodes.BAD_REQUEST, "bad_post_id");
        }

        MomentPostEntity post = momentPostService.getById(postId);
        if (post == null || (post.getDeleted() != null && post.getDeleted() != 0)) {
            return Result.fail(ApiCodes.NOT_FOUND, "not_found");
        }
        if (post.getAuthorId() == null || !visibilityService.canViewAuthor(userId, post.getAuthorId())) {
            return Result.fail(ApiCodes.FORBIDDEN, "forbidden");
        }

        MomentLikeService.ToggleResult out = momentLikeService.toggle(userId, post);
        return Result.ok(new ToggleLikeResponse(out.liked(), out.likeCount()));
    }

    public record CreateCommentRequest(Long postId, String content) {
    }

    public record CreateCommentResponse(Long commentId) {
    }

    @PostMapping("/comment/create")
    public Result<CreateCommentResponse> createComment(@RequestBody CreateCommentRequest req) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        Long postId = req == null ? null : req.postId();
        if (postId == null || postId <= 0) {
            return Result.fail(ApiCodes.BAD_REQUEST, "bad_post_id");
        }

        MomentPostEntity post = momentPostService.getById(postId);
        if (post == null || (post.getDeleted() != null && post.getDeleted() != 0)) {
            return Result.fail(ApiCodes.NOT_FOUND, "not_found");
        }
        if (post.getAuthorId() == null || !visibilityService.canViewAuthor(userId, post.getAuthorId())) {
            return Result.fail(ApiCodes.FORBIDDEN, "forbidden");
        }

        long commentId = momentCommentService.create(userId, post, req == null ? null : req.content());
        return Result.ok(new CreateCommentResponse(commentId));
    }

    public record DeleteCommentRequest(Long commentId) {
    }

    @PostMapping("/comment/delete")
    public Result<Void> deleteComment(@RequestBody DeleteCommentRequest req) {
        Long operatorId = AuthContext.getUserId();
        if (operatorId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        Long commentId = req == null ? null : req.commentId();
        if (commentId == null || commentId <= 0) {
            return Result.fail(ApiCodes.BAD_REQUEST, "bad_comment_id");
        }

        MomentCommentEntity c = momentCommentService.getById(commentId);
        if (c == null || c.getPostId() == null || c.getPostId() <= 0) {
            return Result.fail(ApiCodes.NOT_FOUND, "not_found");
        }

        MomentPostEntity post = momentPostService.getById(c.getPostId());
        if (post == null || (post.getDeleted() != null && post.getDeleted() != 0)) {
            return Result.fail(ApiCodes.NOT_FOUND, "not_found");
        }
        if (post.getAuthorId() == null || !visibilityService.canViewAuthor(operatorId, post.getAuthorId())) {
            return Result.fail(ApiCodes.FORBIDDEN, "forbidden");
        }

        boolean allowed = operatorId.equals(c.getUserId()) || operatorId.equals(post.getAuthorId());
        if (!allowed) {
            return Result.fail(ApiCodes.FORBIDDEN, "forbidden");
        }

        boolean ok = momentCommentService.delete(operatorId, post, commentId);
        if (!ok) {
            return Result.fail(ApiCodes.NOT_FOUND, "not_found");
        }
        return Result.okVoid();
    }

    @GetMapping("/comment/cursor")
    public Result<List<MomentFeedService.CommentDto>> commentCursor(
            @RequestParam Long postId,
            @RequestParam(defaultValue = "20") Long limit,
            @RequestParam(required = false) Long lastId
    ) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        if (postId == null || postId <= 0) {
            return Result.fail(ApiCodes.BAD_REQUEST, "bad_post_id");
        }

        MomentPostEntity post = momentPostService.getById(postId);
        if (post == null || (post.getDeleted() != null && post.getDeleted() != 0)) {
            return Result.fail(ApiCodes.NOT_FOUND, "not_found");
        }
        if (post.getAuthorId() == null || !visibilityService.canViewAuthor(userId, post.getAuthorId())) {
            return Result.fail(ApiCodes.FORBIDDEN, "forbidden");
        }

        return Result.ok(momentFeedService.commentCursor(userId, postId, limit, lastId));
    }
}

