package com.miniim.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.miniim.domain.entity.MomentCommentEntity;
import com.miniim.domain.entity.MomentPostEntity;

public interface MomentCommentService extends IService<MomentCommentEntity> {

    long create(long userId, MomentPostEntity post, String content);

    boolean delete(long operatorId, MomentPostEntity post, long commentId);
}

