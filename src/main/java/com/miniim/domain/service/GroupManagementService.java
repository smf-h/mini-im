package com.miniim.domain.service;

import com.miniim.domain.enums.MemberRole;

import java.time.LocalDateTime;
import java.util.List;

public interface GroupManagementService {

    GroupProfile profileById(long requesterId, long groupId);

    GroupProfile profileByCode(long requesterId, String groupCode);

    List<GroupMember> memberList(long requesterId, long groupId);

    void leave(long userId, long groupId);

    void kick(long operatorId, long groupId, long targetUserId);

    void setAdmin(long operatorId, long groupId, long targetUserId, boolean admin);

    void transferOwner(long operatorId, long groupId, long newOwnerUserId);

    ResetGroupCodeResult resetGroupCode(long operatorId, long groupId);

    record GroupProfile(
            Long groupId,
            String name,
            String avatarUrl,
            String groupCode,
            Long createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            Long memberCount,
            MemberRole myRole,
            Boolean isMember
    ) {
    }

    record GroupMember(
            Long userId,
            String username,
            String nickname,
            String avatarUrl,
            MemberRole role,
            LocalDateTime joinAt
    ) {
    }

    record ResetGroupCodeResult(
            String groupCode,
            LocalDateTime groupCodeUpdatedAt,
            LocalDateTime groupCodeNextResetAt
    ) {
    }
}

