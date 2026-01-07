package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.miniim.domain.entity.MomentCommentEntity;
import com.miniim.domain.entity.MomentLikeEntity;
import com.miniim.domain.entity.MomentPostEntity;
import com.miniim.domain.mapper.MomentCommentMapper;
import com.miniim.domain.mapper.MomentLikeMapper;
import com.miniim.domain.mapper.MomentPostMapper;
import com.miniim.domain.service.FriendRelationService;
import com.miniim.domain.service.MomentFeedService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class MomentFeedServiceImpl implements MomentFeedService {

    private final FriendRelationService friendRelationService;
    private final MomentPostMapper momentPostMapper;
    private final MomentLikeMapper momentLikeMapper;
    private final MomentCommentMapper momentCommentMapper;

    public MomentFeedServiceImpl(
            FriendRelationService friendRelationService,
            MomentPostMapper momentPostMapper,
            MomentLikeMapper momentLikeMapper,
            MomentCommentMapper momentCommentMapper
    ) {
        this.friendRelationService = friendRelationService;
        this.momentPostMapper = momentPostMapper;
        this.momentLikeMapper = momentLikeMapper;
        this.momentCommentMapper = momentCommentMapper;
    }

    @Override
    public List<PostDto> feedCursor(long viewerId, Long limit, Long lastId) {
        if (viewerId <= 0) {
            return List.of();
        }

        int safeLimit = clampLimit(limit, 20);
        Set<Long> friendIds = friendRelationService.friendIdSet(viewerId);
        List<Long> authors = new ArrayList<>(Math.min(friendIds.size() + 1, 2001));
        authors.add(viewerId);
        int cap = 2000;
        for (Long fid : friendIds) {
            if (fid == null || fid <= 0) continue;
            authors.add(fid);
            if (authors.size() >= cap + 1) break;
        }

        LambdaQueryWrapper<MomentPostEntity> w = new LambdaQueryWrapper<>();
        w.eq(MomentPostEntity::getDeleted, 0);
        w.in(MomentPostEntity::getAuthorId, authors);
        if (lastId != null) {
            w.lt(MomentPostEntity::getId, lastId);
        }
        w.orderByDesc(MomentPostEntity::getId);
        w.last("limit " + safeLimit);
        List<MomentPostEntity> posts = momentPostMapper.selectList(w);
        return toPostDtos(viewerId, posts);
    }

    @Override
    public List<PostDto> userCursor(long viewerId, long authorId, Long limit, Long lastId) {
        if (viewerId <= 0 || authorId <= 0) {
            return List.of();
        }
        int safeLimit = clampLimit(limit, 20);

        LambdaQueryWrapper<MomentPostEntity> w = new LambdaQueryWrapper<>();
        w.eq(MomentPostEntity::getDeleted, 0);
        w.eq(MomentPostEntity::getAuthorId, authorId);
        if (lastId != null) {
            w.lt(MomentPostEntity::getId, lastId);
        }
        w.orderByDesc(MomentPostEntity::getId);
        w.last("limit " + safeLimit);
        List<MomentPostEntity> posts = momentPostMapper.selectList(w);
        return toPostDtos(viewerId, posts);
    }

    @Override
    public List<CommentDto> commentCursor(long viewerId, long postId, Long limit, Long lastId) {
        if (viewerId <= 0 || postId <= 0) {
            return List.of();
        }
        int safeLimit = clampLimit(limit, 20);

        LambdaQueryWrapper<MomentCommentEntity> w = new LambdaQueryWrapper<>();
        w.eq(MomentCommentEntity::getPostId, postId);
        w.eq(MomentCommentEntity::getDeleted, 0);
        if (lastId != null) {
            w.lt(MomentCommentEntity::getId, lastId);
        }
        w.orderByDesc(MomentCommentEntity::getId);
        w.last("limit " + safeLimit);
        List<MomentCommentEntity> comments = momentCommentMapper.selectList(w);

        List<CommentDto> out = new ArrayList<>(comments.size());
        for (MomentCommentEntity c : comments) {
            if (c == null || c.getId() == null) continue;
            out.add(new CommentDto(
                    c.getId(),
                    c.getPostId(),
                    c.getUserId(),
                    c.getContent(),
                    c.getCreatedAt()
            ));
        }
        return out;
    }

    private List<PostDto> toPostDtos(long viewerId, List<MomentPostEntity> posts) {
        if (posts == null || posts.isEmpty()) {
            return List.of();
        }

        List<Long> postIds = new ArrayList<>(posts.size());
        for (MomentPostEntity p : posts) {
            if (p != null && p.getId() != null && p.getId() > 0) {
                postIds.add(p.getId());
            }
        }

        Set<Long> likedSet = new HashSet<>();
        if (!postIds.isEmpty()) {
            List<MomentLikeEntity> likes = momentLikeMapper.selectList(new LambdaQueryWrapper<MomentLikeEntity>()
                    .eq(MomentLikeEntity::getUserId, viewerId)
                    .in(MomentLikeEntity::getPostId, postIds));
            for (MomentLikeEntity like : likes) {
                if (like != null && like.getPostId() != null) {
                    likedSet.add(like.getPostId());
                }
            }
        }

        List<PostDto> out = new ArrayList<>(posts.size());
        for (MomentPostEntity p : posts) {
            if (p == null || p.getId() == null) continue;
            out.add(new PostDto(
                    p.getId(),
                    p.getAuthorId(),
                    p.getContent(),
                    p.getLikeCount() == null ? 0 : p.getLikeCount(),
                    p.getCommentCount() == null ? 0 : p.getCommentCount(),
                    likedSet.contains(p.getId()),
                    p.getCreatedAt()
            ));
        }
        return out;
    }

    private static int clampLimit(Long limit, int dft) {
        long raw = limit == null ? dft : limit;
        if (raw < 1) raw = 1;
        if (raw > 100) raw = 100;
        return (int) raw;
    }
}

