package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.miniim.domain.cache.FriendIdsCache;
import com.miniim.domain.entity.FriendRelationEntity;
import com.miniim.domain.mapper.FriendRelationMapper;
import com.miniim.domain.service.FriendRelationService;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class FriendRelationServiceImpl extends ServiceImpl<FriendRelationMapper, FriendRelationEntity> implements FriendRelationService {

    private final FriendIdsCache friendIdsCache;

    public FriendRelationServiceImpl(FriendIdsCache friendIdsCache) {
        this.friendIdsCache = friendIdsCache;
    }

    @Override
    public List<FriendRelationEntity> listByUserId(Long userId) {
        LambdaQueryWrapper<FriendRelationEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.nested(w -> w.eq(FriendRelationEntity::getUser1Id, userId)
                .or()
                .eq(FriendRelationEntity::getUser2Id, userId));
        return this.list(wrapper);
    }

    @Override
    public List<FriendRelationEntity> cursorByUserId(Long userId, Long limit, Long lastId) {
        int safeLimit = 10;
        if (limit != null) {
            long raw = limit;
            if (raw < 1) {
                raw = 1;
            }
            if (raw > 100) {
                raw = 100;
            }
            safeLimit = (int) raw;
        }

        LambdaQueryWrapper<FriendRelationEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.nested(w -> w.eq(FriendRelationEntity::getUser1Id, userId)
                .or()
                .eq(FriendRelationEntity::getUser2Id, userId));
        wrapper.orderByDesc(FriendRelationEntity::getId);
        if (lastId != null) {
            wrapper.lt(FriendRelationEntity::getId, lastId);
        }
        wrapper.last("limit " + safeLimit);
        return this.list(wrapper);
    }

    @Override
    public Set<Long> friendIdSet(long userId) {
        if (userId <= 0) {
            return Set.of();
        }

        Set<Long> cached = friendIdsCache.get(userId);
        if (cached != null) {
            return cached;
        }

        List<FriendRelationEntity> rels = listByUserId(userId);
        if (rels == null || rels.isEmpty()) {
            friendIdsCache.put(userId, List.of());
            return Set.of();
        }

        Set<Long> out = new HashSet<>();
        for (FriendRelationEntity r : rels) {
            if (r == null) {
                continue;
            }
            Long a = r.getUser1Id();
            Long b = r.getUser2Id();
            if (a == null || b == null) {
                continue;
            }
            if (userId == a && b > 0) {
                out.add(b);
            } else if (userId == b && a > 0) {
                out.add(a);
            }
        }

        friendIdsCache.put(userId, out);
        return out;
    }

    @Override
    public void evictFriendIdSet(long userId) {
        if (userId <= 0) {
            return;
        }
        friendIdsCache.evict(userId);
    }
}
