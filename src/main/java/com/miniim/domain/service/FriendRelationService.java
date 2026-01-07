package com.miniim.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.miniim.domain.entity.FriendRelationEntity;

import java.util.Set;
import java.util.List;

public interface FriendRelationService extends IService<FriendRelationEntity> {
    List<FriendRelationEntity> listByUserId(Long userId);

    /**
     * 按 id 倒序的游标分页：返回 id < lastId 的下一页。
     * lastId 为空表示从最新开始。
     */
    List<FriendRelationEntity> cursorByUserId(Long userId, Long limit, Long lastId);

    /**
     * 获取好友 id 集合（仅 id）：优先走缓存，miss 则回源 DB 并回填缓存。
     */
    Set<Long> friendIdSet(long userId);

    /**
     * 主动失效好友 id 缓存（好友关系发生变化时调用）。
     */
    void evictFriendIdSet(long userId);
}
