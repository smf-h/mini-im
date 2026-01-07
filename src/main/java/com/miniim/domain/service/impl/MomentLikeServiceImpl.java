package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.miniim.domain.entity.MomentLikeEntity;
import com.miniim.domain.entity.MomentPostEntity;
import com.miniim.domain.mapper.MomentLikeMapper;
import com.miniim.domain.mapper.MomentPostMapper;
import com.miniim.domain.service.MomentLikeService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MomentLikeServiceImpl extends ServiceImpl<MomentLikeMapper, MomentLikeEntity> implements MomentLikeService {

    private final MomentPostMapper momentPostMapper;

    public MomentLikeServiceImpl(MomentPostMapper momentPostMapper) {
        this.momentPostMapper = momentPostMapper;
    }

    @Transactional
    @Override
    public ToggleResult toggle(long userId, MomentPostEntity post) {
        if (userId <= 0 || post == null || post.getId() == null || post.getId() <= 0) {
            throw new IllegalArgumentException("bad_request");
        }
        long postId = post.getId();

        long existed = this.count(new LambdaQueryWrapper<MomentLikeEntity>()
                .eq(MomentLikeEntity::getPostId, postId)
                .eq(MomentLikeEntity::getUserId, userId));
        if (existed > 0) {
            boolean deleted = this.remove(new LambdaQueryWrapper<MomentLikeEntity>()
                    .eq(MomentLikeEntity::getPostId, postId)
                    .eq(MomentLikeEntity::getUserId, userId));
            if (deleted) {
                momentPostMapper.update(null, new LambdaUpdateWrapper<MomentPostEntity>()
                        .eq(MomentPostEntity::getId, postId)
                        .setSql("like_count = IF(like_count > 0, like_count - 1, 0)"));
            }
            MomentPostEntity latest = momentPostMapper.selectById(postId);
            int likeCount = latest == null || latest.getLikeCount() == null ? 0 : latest.getLikeCount();
            return new ToggleResult(false, likeCount);
        }

        try {
            MomentLikeEntity like = new MomentLikeEntity();
            like.setPostId(postId);
            like.setUserId(userId);
            this.save(like);
            momentPostMapper.update(null, new LambdaUpdateWrapper<MomentPostEntity>()
                    .eq(MomentPostEntity::getId, postId)
                    .setSql("like_count = like_count + 1"));
        } catch (DuplicateKeyException ignore) {
            // concurrent insert, treat as liked
        }

        MomentPostEntity latest = momentPostMapper.selectById(postId);
        int likeCount = latest == null || latest.getLikeCount() == null ? 0 : latest.getLikeCount();
        return new ToggleResult(true, likeCount);
    }
}

