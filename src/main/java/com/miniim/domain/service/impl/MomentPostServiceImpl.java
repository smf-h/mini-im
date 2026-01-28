package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.miniim.common.content.ForbiddenWordFilter;
import com.miniim.domain.entity.MomentPostEntity;
import com.miniim.domain.mapper.MomentPostMapper;
import com.miniim.domain.service.MomentPostService;
import org.springframework.stereotype.Service;

@Service
public class MomentPostServiceImpl extends ServiceImpl<MomentPostMapper, MomentPostEntity> implements MomentPostService {

    private static final int MAX_CONTENT_LEN = 500;

    private final ForbiddenWordFilter forbiddenWordFilter;

    public MomentPostServiceImpl(ForbiddenWordFilter forbiddenWordFilter) {
        this.forbiddenWordFilter = forbiddenWordFilter;
    }

    @Override
    public long create(long authorId, String content) {
        if (authorId <= 0) {
            throw new IllegalArgumentException("unauthorized");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("empty_content");
        }
        String trimmed = content.trim();
        if (trimmed.length() > MAX_CONTENT_LEN) {
            throw new IllegalArgumentException("content_too_long");
        }

        String sanitized = forbiddenWordFilter.sanitize(trimmed);
        MomentPostEntity post = new MomentPostEntity();
        post.setAuthorId(authorId);
        post.setContent(sanitized);
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setDeleted(0);
        this.save(post);
        return post.getId() == null ? 0 : post.getId();
    }

    @Override
    public boolean softDelete(long postId, long operatorId) {
        if (postId <= 0 || operatorId <= 0) {
            throw new IllegalArgumentException("bad_request");
        }
        return this.update(new LambdaUpdateWrapper<MomentPostEntity>()
                .eq(MomentPostEntity::getId, postId)
                .eq(MomentPostEntity::getAuthorId, operatorId)
                .eq(MomentPostEntity::getDeleted, 0)
                .set(MomentPostEntity::getDeleted, 1));
    }
}

