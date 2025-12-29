package com.miniim.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.miniim.domain.entity.FriendRelationEntity;

import java.util.List;

public interface FriendRelationService extends IService<FriendRelationEntity> {
    List<FriendRelationEntity> listByUserId(Long userId);

    /**
     * 按 id 倒序的游标分页：返回 id < lastId 的下一页。
     * lastId 为空表示从最新开始。
     */
    List<FriendRelationEntity> cursorByUserId(Long userId, Long limit, Long lastId);
}
