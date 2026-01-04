package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.miniim.domain.entity.FriendRelationEntity;
import com.miniim.domain.mapper.FriendRelationMapper;
import com.miniim.domain.service.FriendRelationService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public  class FriendRelationServiceImpl extends ServiceImpl<FriendRelationMapper, FriendRelationEntity> implements FriendRelationService {
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
}
