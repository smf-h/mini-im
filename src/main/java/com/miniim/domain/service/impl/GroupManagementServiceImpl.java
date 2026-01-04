package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.miniim.domain.entity.GroupEntity;
import com.miniim.domain.entity.GroupMemberEntity;
import com.miniim.domain.entity.UserEntity;
import com.miniim.domain.enums.MemberRole;
import com.miniim.domain.mapper.GroupMapper;
import com.miniim.domain.mapper.GroupMemberMapper;
import com.miniim.domain.mapper.UserMapper;
import com.miniim.domain.service.CodeService;
import com.miniim.domain.service.GroupManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GroupManagementServiceImpl implements GroupManagementService {

    private static final LocalDateTime MUTE_FOREVER = LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_000_000);

    private final GroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final UserMapper userMapper;
    private final CodeService codeService;

    @Override
    public GroupProfile profileById(long requesterId, long groupId) {
        if (requesterId <= 0) {
            throw new IllegalArgumentException("unauthorized");
        }
        if (groupId <= 0) {
            throw new IllegalArgumentException("bad_group_id");
        }
        GroupEntity g = groupMapper.selectById(groupId);
        if (g == null) {
            throw new IllegalArgumentException("not_found");
        }
        g = codeService.ensureGroupCode(groupId);

        MemberRole myRole = getRole(groupId, requesterId);
        boolean isMember = myRole != null;
        long memberCount = groupMemberMapper.selectCount(new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, groupId));

        return new GroupProfile(
                g.getId(),
                g.getName(),
                g.getAvatarUrl(),
                g.getGroupCode(),
                g.getCreatedBy(),
                g.getCreatedAt(),
                g.getUpdatedAt(),
                memberCount,
                myRole,
                isMember
        );
    }

    @Override
    public GroupProfile profileByCode(long requesterId, String groupCode) {
        if (requesterId <= 0) {
            throw new IllegalArgumentException("unauthorized");
        }
        String code = groupCode == null ? "" : groupCode.trim();
        code = code.toUpperCase();
        if (code.isEmpty()) {
            throw new IllegalArgumentException("missing_group_code");
        }
        if (code.length() > 16) {
            throw new IllegalArgumentException("bad_group_code");
        }
        GroupEntity g = groupMapper.selectOne(new LambdaQueryWrapper<GroupEntity>()
                .eq(GroupEntity::getGroupCode, code)
                .last("limit 1"));
        if (g == null || g.getId() == null) {
            throw new IllegalArgumentException("not_found");
        }
        return profileById(requesterId, g.getId());
    }

    @Override
    public List<GroupMember> memberList(long requesterId, long groupId) {
        if (requesterId <= 0) {
            throw new IllegalArgumentException("unauthorized");
        }
        if (groupId <= 0) {
            throw new IllegalArgumentException("bad_group_id");
        }
        MemberRole myRole = getRole(groupId, requesterId);
        if (myRole == null) {
            throw new IllegalArgumentException("forbidden");
        }
        List<GroupMemberEntity> members = groupMemberMapper.selectList(new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, groupId)
                .orderByAsc(GroupMemberEntity::getRole)
                .orderByAsc(GroupMemberEntity::getJoinAt));
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        List<Long> uids = new ArrayList<>();
        for (GroupMemberEntity m : members) {
            if (m != null && m.getUserId() != null && m.getUserId() > 0) {
                uids.add(m.getUserId());
            }
        }
        List<UserEntity> users = uids.isEmpty() ? List.of() : userMapper.selectBatchIds(uids);
        Map<Long, UserEntity> userMap = new HashMap<>();
        if (users != null) {
            for (UserEntity u : users) {
                if (u != null && u.getId() != null) {
                    userMap.put(u.getId(), u);
                }
            }
        }
        List<GroupMember> out = new ArrayList<>();
        for (GroupMemberEntity m : members) {
            if (m == null || m.getUserId() == null) {
                continue;
            }
            UserEntity u = userMap.get(m.getUserId());
            out.add(new GroupMember(
                    m.getUserId(),
                    u == null ? null : u.getUsername(),
                    u == null ? null : u.getNickname(),
                    u == null ? null : u.getAvatarUrl(),
                    m.getRole(),
                    m.getJoinAt(),
                    m.getSpeakMuteUntil()
            ));
        }
        return out;
    }

    @Transactional
    @Override
    public void muteMember(long operatorId, long groupId, long targetUserId, long durationSeconds) {
        if (operatorId <= 0) {
            throw new IllegalArgumentException("unauthorized");
        }
        if (groupId <= 0 || targetUserId <= 0) {
            throw new IllegalArgumentException("bad_request");
        }
        if (operatorId == targetUserId) {
            throw new IllegalArgumentException("cannot_mute_self");
        }

        MemberRole op = getRole(groupId, operatorId);
        if (op != MemberRole.OWNER && op != MemberRole.ADMIN) {
            throw new IllegalArgumentException("forbidden");
        }

        GroupMemberEntity target = groupMemberMapper.selectOne(new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, groupId)
                .eq(GroupMemberEntity::getUserId, targetUserId)
                .last("limit 1"));
        if (target == null) {
            throw new IllegalArgumentException("target_not_member");
        }
        MemberRole tr = target.getRole();
        if (tr == MemberRole.OWNER) {
            throw new IllegalArgumentException("forbidden");
        }

        boolean allowed = false;
        if (op == MemberRole.OWNER) {
            allowed = true;
        } else if (op == MemberRole.ADMIN) {
            allowed = tr == MemberRole.MEMBER;
        }
        if (!allowed) {
            throw new IllegalArgumentException("forbidden");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime muteUntil = null;
        if (durationSeconds == 0) {
            muteUntil = null;
        } else if (durationSeconds == -1) {
            muteUntil = MUTE_FOREVER;
        } else if (durationSeconds == 600 || durationSeconds == 3600 || durationSeconds == 86400) {
            muteUntil = now.plus(durationSeconds, ChronoUnit.SECONDS);
        } else {
            throw new IllegalArgumentException("bad_duration");
        }

        groupMemberMapper.update(null, new LambdaUpdateWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, groupId)
                .eq(GroupMemberEntity::getUserId, targetUserId)
                .set(GroupMemberEntity::getSpeakMuteUntil, muteUntil)
                .set(GroupMemberEntity::getUpdatedAt, now));
    }

    @Transactional
    @Override
    public void leave(long userId, long groupId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("unauthorized");
        }
        if (groupId <= 0) {
            throw new IllegalArgumentException("bad_group_id");
        }
        MemberRole myRole = getRole(groupId, userId);
        if (myRole == null) {
            throw new IllegalArgumentException("not_member");
        }
        if (myRole == MemberRole.OWNER) {
            throw new IllegalArgumentException("owner_must_transfer_before_leaving");
        }
        groupMemberMapper.delete(new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, groupId)
                .eq(GroupMemberEntity::getUserId, userId));
    }

    @Transactional
    @Override
    public void kick(long operatorId, long groupId, long targetUserId) {
        if (operatorId <= 0) {
            throw new IllegalArgumentException("unauthorized");
        }
        if (groupId <= 0 || targetUserId <= 0) {
            throw new IllegalArgumentException("bad_request");
        }
        if (operatorId == targetUserId) {
            throw new IllegalArgumentException("cannot_kick_self");
        }
        MemberRole op = getRole(groupId, operatorId);
        if (op == null) {
            throw new IllegalArgumentException("forbidden");
        }
        GroupMemberEntity target = groupMemberMapper.selectOne(new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, groupId)
                .eq(GroupMemberEntity::getUserId, targetUserId)
                .last("limit 1"));
        if (target == null) {
            throw new IllegalArgumentException("target_not_member");
        }
        MemberRole tr = target.getRole();

        boolean allowed = false;
        if (op == MemberRole.OWNER) {
            allowed = tr != MemberRole.OWNER;
        } else if (op == MemberRole.ADMIN) {
            allowed = tr == MemberRole.MEMBER;
        }
        if (!allowed) {
            throw new IllegalArgumentException("forbidden");
        }

        groupMemberMapper.delete(new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, groupId)
                .eq(GroupMemberEntity::getUserId, targetUserId));
    }

    @Transactional
    @Override
    public void setAdmin(long operatorId, long groupId, long targetUserId, boolean admin) {
        if (operatorId <= 0) {
            throw new IllegalArgumentException("unauthorized");
        }
        if (groupId <= 0 || targetUserId <= 0) {
            throw new IllegalArgumentException("bad_request");
        }
        MemberRole op = getRole(groupId, operatorId);
        if (op != MemberRole.OWNER) {
            throw new IllegalArgumentException("forbidden");
        }

        GroupMemberEntity target = groupMemberMapper.selectOne(new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, groupId)
                .eq(GroupMemberEntity::getUserId, targetUserId)
                .last("limit 1"));
        if (target == null) {
            throw new IllegalArgumentException("target_not_member");
        }
        if (target.getRole() == MemberRole.OWNER) {
            throw new IllegalArgumentException("cannot_change_owner_role");
        }

        MemberRole newRole = admin ? MemberRole.ADMIN : MemberRole.MEMBER;
        groupMemberMapper.update(null, new LambdaUpdateWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, groupId)
                .eq(GroupMemberEntity::getUserId, targetUserId)
                .set(GroupMemberEntity::getRole, newRole)
                .set(GroupMemberEntity::getUpdatedAt, LocalDateTime.now()));
    }

    @Transactional
    @Override
    public void transferOwner(long operatorId, long groupId, long newOwnerUserId) {
        if (operatorId <= 0) {
            throw new IllegalArgumentException("unauthorized");
        }
        if (groupId <= 0 || newOwnerUserId <= 0) {
            throw new IllegalArgumentException("bad_request");
        }
        MemberRole op = getRole(groupId, operatorId);
        if (op != MemberRole.OWNER) {
            throw new IllegalArgumentException("forbidden");
        }
        if (operatorId == newOwnerUserId) {
            throw new IllegalArgumentException("already_owner");
        }

        GroupMemberEntity target = groupMemberMapper.selectOne(new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, groupId)
                .eq(GroupMemberEntity::getUserId, newOwnerUserId)
                .last("limit 1"));
        if (target == null) {
            throw new IllegalArgumentException("target_not_member");
        }

        LocalDateTime now = LocalDateTime.now();
        groupMemberMapper.update(null, new LambdaUpdateWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, groupId)
                .eq(GroupMemberEntity::getUserId, operatorId)
                .set(GroupMemberEntity::getRole, MemberRole.MEMBER)
                .set(GroupMemberEntity::getUpdatedAt, now));
        groupMemberMapper.update(null, new LambdaUpdateWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, groupId)
                .eq(GroupMemberEntity::getUserId, newOwnerUserId)
                .set(GroupMemberEntity::getRole, MemberRole.OWNER)
                .set(GroupMemberEntity::getUpdatedAt, now));
    }

    @Transactional
    @Override
    public ResetGroupCodeResult resetGroupCode(long operatorId, long groupId) {
        if (operatorId <= 0) {
            throw new IllegalArgumentException("unauthorized");
        }
        if (groupId <= 0) {
            throw new IllegalArgumentException("bad_group_id");
        }
        MemberRole op = getRole(groupId, operatorId);
        if (op != MemberRole.OWNER && op != MemberRole.ADMIN) {
            throw new IllegalArgumentException("forbidden");
        }
        LocalDateTime now = LocalDateTime.now();
        String code = codeService.resetGroupCode(groupId, now);
        GroupEntity g = groupMapper.selectById(groupId);
        LocalDateTime updatedAt = g == null ? null : g.getGroupCodeUpdatedAt();
        return new ResetGroupCodeResult(code, updatedAt, codeService.nextResetAt(updatedAt));
    }

    private MemberRole getRole(long groupId, long userId) {
        GroupMemberEntity m = groupMemberMapper.selectOne(new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, groupId)
                .eq(GroupMemberEntity::getUserId, userId)
                .last("limit 1"));
        return m == null ? null : m.getRole();
    }
}
