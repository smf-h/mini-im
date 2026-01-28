package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.miniim.domain.entity.GroupEntity;
import com.miniim.domain.entity.GroupMemberEntity;
import com.miniim.domain.enums.MemberRole;
import com.miniim.domain.mapper.GroupMapper;
import com.miniim.domain.mapper.GroupMemberMapper;
import com.miniim.domain.service.CodeService;
import com.miniim.domain.service.GroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GroupServiceImpl extends ServiceImpl<GroupMapper, GroupEntity> implements GroupService {

    private final GroupMemberMapper groupMemberMapper;
    private final CodeService codeService;

    @Override
    public GroupEntity createGroup(Long creatorUserId, String name, List<Long> memberUserIds) {
        if (creatorUserId == null || creatorUserId <= 0) {
            throw new IllegalArgumentException("missing_creator_user_id");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("missing_group_name");
        }

        GroupEntity group = new GroupEntity();
        group.setName(name.trim());
        group.setCreatedBy(creatorUserId);
        group.setGroupCode(codeService.newUniqueGroupCode());
        group.setGroupCodeUpdatedAt(LocalDateTime.now());
        this.save(group);

        LocalDateTime now = LocalDateTime.now();
        GroupMemberEntity owner = new GroupMemberEntity();
        owner.setGroupId(group.getId());
        owner.setUserId(creatorUserId);
        owner.setRole(MemberRole.OWNER);
        owner.setJoinAt(now);
        owner.setCreatedAt(now);
        owner.setUpdatedAt(now);
        groupMemberMapper.insert(owner);

        return group;
    }

    @Override
    public List<GroupEntity> cursorByUserId(Long userId, int limit, LocalDateTime lastUpdatedAt, Long lastId) {
        if (userId == null || userId <= 0) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(100, limit));
        return this.baseMapper.selectGroupsForUserCursor(userId, safeLimit, lastUpdatedAt, lastId);
    }
}
