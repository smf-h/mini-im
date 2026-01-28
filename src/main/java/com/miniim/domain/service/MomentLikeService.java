package com.miniim.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.miniim.domain.entity.MomentLikeEntity;
import com.miniim.domain.entity.MomentPostEntity;

public interface MomentLikeService extends IService<MomentLikeEntity> {

    ToggleResult toggle(long userId, MomentPostEntity post);

    record ToggleResult(boolean liked, int likeCount) {
    }
}

