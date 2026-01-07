package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.miniim.common.content.ForbiddenWordFilter;
import com.miniim.domain.entity.MomentCommentEntity;
import com.miniim.domain.entity.MomentPostEntity;
import com.miniim.domain.mapper.MomentCommentMapper;
import com.miniim.domain.mapper.MomentPostMapper;
import com.miniim.domain.service.MomentCommentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MomentCommentServiceImpl extends ServiceImpl<MomentCommentMapper, MomentCommentEntity> implements MomentCommentService {

    private static final int MAX_CONTENT_LEN = 500;

    private final ForbiddenWordFilter forbiddenWordFilter;
    private final MomentPostMapper momentPostMapper;

    public MomentCommentServiceImpl(ForbiddenWordFilter forbiddenWordFilter, MomentPostMapper momentPostMapper) {
        this.forbiddenWordFilter = forbiddenWordFilter;
        this.momentPostMapper = momentPostMapper;
    }

    @Transactional
    @Override
    public long create(long userId, MomentPostEntity post, String content) {
        if (userId <= 0 || post == null || post.getId() == null || post.getId() <= 0) {
            throw new IllegalArgumentException("bad_request");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("empty_content");
        }
        String trimmed = content.trim();
        if (trimmed.length() > MAX_CONTENT_LEN) {
            throw new IllegalArgumentException("content_too_long");
        }

        String sanitized = forbiddenWordFilter.sanitize(trimmed);
        MomentCommentEntity c = new MomentCommentEntity();
        c.setPostId(post.getId());
        c.setUserId(userId);
        c.setContent(sanitized);
        c.setDeleted(0);
        this.save(c);

        momentPostMapper.update(null, new LambdaUpdateWrapper<MomentPostEntity>()
                .eq(MomentPostEntity::getId, post.getId())
                .setSql("comment_count = comment_count + 1"));
        return c.getId() == null ? 0 : c.getId();
    }

    @Transactional
    @Override
    public boolean delete(long operatorId, MomentPostEntity post, long commentId) {
        if (operatorId <= 0 || post == null || post.getId() == null || post.getId() <= 0 || commentId <= 0) {
            throw new IllegalArgumentException("bad_request");
        }

        MomentCommentEntity c = this.getById(commentId);
        if (c == null) {
            return false;
        }
        if (c.getPostId() == null || !c.getPostId().equals(post.getId())) {
            return false;
        }

        boolean updated = this.update(new LambdaUpdateWrapper<MomentCommentEntity>()
                .eq(MomentCommentEntity::getId, commentId)
                .eq(MomentCommentEntity::getDeleted, 0)
                .set(MomentCommentEntity::getDeleted, 1));
        if (updated) {
            momentPostMapper.update(null, new LambdaUpdateWrapper<MomentPostEntity>()
                    .eq(MomentPostEntity::getId, post.getId())
                    .setSql("comment_count = IF(comment_count > 0, comment_count - 1, 0)"));
        }
        return true;
    }
}
