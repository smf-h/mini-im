package com.miniim.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.miniim.domain.entity.GroupEntity;

import java.time.LocalDateTime;
import java.util.List;

public interface GroupService extends IService<GroupEntity> {
    GroupEntity createGroup(Long creatorUserId, String name, List<Long> memberUserIds);

    List<GroupEntity> cursorByUserId(Long userId, int limit, LocalDateTime lastUpdatedAt, Long lastId);
}
