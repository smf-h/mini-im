package com.miniim.domain.service;

import java.time.LocalDateTime;
import java.util.List;

public interface MomentFeedService {

    List<PostDto> feedCursor(long viewerId, Long limit, Long lastId);

    List<PostDto> userCursor(long viewerId, long authorId, Long limit, Long lastId);

    List<CommentDto> commentCursor(long viewerId, long postId, Long limit, Long lastId);

    record PostDto(
            Long id,
            Long authorId,
            String content,
            Integer likeCount,
            Integer commentCount,
            Boolean likedByMe,
            LocalDateTime createdAt
    ) {
    }

    record CommentDto(
            Long id,
            Long postId,
            Long userId,
            String content,
            LocalDateTime createdAt
    ) {
    }
}

