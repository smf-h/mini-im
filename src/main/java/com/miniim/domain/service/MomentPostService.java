package com.miniim.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.miniim.domain.entity.MomentPostEntity;

public interface MomentPostService extends IService<MomentPostEntity> {

    long create(long authorId, String content);

    boolean softDelete(long postId, long operatorId);
}

