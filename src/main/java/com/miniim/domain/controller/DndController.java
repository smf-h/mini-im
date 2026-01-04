package com.miniim.domain.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.miniim.auth.web.AuthContext;
import com.miniim.common.api.ApiCodes;
import com.miniim.common.api.Result;
import com.miniim.domain.entity.GroupMemberEntity;
import com.miniim.domain.entity.SingleChatEntity;
import com.miniim.domain.entity.SingleChatMemberEntity;
import com.miniim.domain.service.GroupMemberService;
import com.miniim.domain.service.SingleChatMemberService;
import com.miniim.domain.service.SingleChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
@RestController
@RequestMapping("/dnd")
public class DndController {

    private static final LocalDateTime MUTE_FOREVER = LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_000_000);

    private final SingleChatService singleChatService;
    private final SingleChatMemberService singleChatMemberService;
    private final GroupMemberService groupMemberService;

    public record DndListResponse(
            List<String> dmPeerUserIds,
            List<String> groupIds
    ) {
    }

    @GetMapping("/list")
    public Result<DndListResponse> list() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }

        LocalDateTime now = LocalDateTime.now();

        List<SingleChatMemberEntity> mutedDmMembers = singleChatMemberService.list(new LambdaQueryWrapper<SingleChatMemberEntity>()
                .eq(SingleChatMemberEntity::getUserId, userId)
                .gt(SingleChatMemberEntity::getMuteUntil, now));
        List<Long> chatIds = new ArrayList<>();
        if (mutedDmMembers != null) {
            for (SingleChatMemberEntity m : mutedDmMembers) {
                if (m == null || m.getSingleChatId() == null || m.getSingleChatId() <= 0) {
                    continue;
                }
                chatIds.add(m.getSingleChatId());
            }
        }

        Set<Long> peerUserIds = new HashSet<>();
        if (!chatIds.isEmpty()) {
            List<SingleChatEntity> chats = singleChatService.listByIds(chatIds);
            if (chats != null) {
                for (SingleChatEntity c : chats) {
                    if (c == null || c.getId() == null || c.getId() <= 0) {
                        continue;
                    }
                    Long user1Id = c.getUser1Id();
                    Long user2Id = c.getUser2Id();
                    if (user1Id == null || user2Id == null) {
                        continue;
                    }
                    if (userId.equals(user1Id)) {
                        peerUserIds.add(user2Id);
                    } else if (userId.equals(user2Id)) {
                        peerUserIds.add(user1Id);
                    }
                }
            }
        }

        List<GroupMemberEntity> mutedGroupMembers = groupMemberService.list(new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getUserId, userId)
                .gt(GroupMemberEntity::getMuteUntil, now));
        Set<Long> groupIds = new HashSet<>();
        if (mutedGroupMembers != null) {
            for (GroupMemberEntity m : mutedGroupMembers) {
                if (m == null || m.getGroupId() == null || m.getGroupId() <= 0) {
                    continue;
                }
                groupIds.add(m.getGroupId());
            }
        }

        List<String> dmOut = new ArrayList<>();
        for (Long id : peerUserIds) {
            if (id != null && id > 0) {
                dmOut.add(String.valueOf(id));
            }
        }
        List<String> groupOut = new ArrayList<>();
        for (Long id : groupIds) {
            if (id != null && id > 0) {
                groupOut.add(String.valueOf(id));
            }
        }

        return Result.ok(new DndListResponse(dmOut, groupOut));
    }

    public record DmSetRequest(String peerUserId, boolean muted) {
    }

    @PostMapping("/dm/set")
    public Result<Void> setDm(@RequestBody DmSetRequest req) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        if (req == null || req.peerUserId == null || req.peerUserId.isBlank()) {
            return Result.fail(ApiCodes.BAD_REQUEST, "missing_peer_user_id");
        }

        long peerUserId;
        try {
            peerUserId = Long.parseLong(req.peerUserId.trim());
        } catch (NumberFormatException ignore) {
            return Result.fail(ApiCodes.BAD_REQUEST, "invalid_peer_user_id");
        }
        if (peerUserId <= 0) {
            return Result.fail(ApiCodes.BAD_REQUEST, "invalid_peer_user_id");
        }
        if (userId.equals(peerUserId)) {
            return Result.fail(ApiCodes.BAD_REQUEST, "cannot_mute_self_chat");
        }

        long user1Id = Math.min(userId, peerUserId);
        long user2Id = Math.max(userId, peerUserId);
        Long singleChatId = singleChatService.getOrCreateSingleChatId(user1Id, user2Id);
        if (singleChatId == null || singleChatId <= 0) {
            return Result.fail(ApiCodes.INTERNAL_ERROR, "internal_error");
        }

        singleChatMemberService.ensureMembers(singleChatId, userId, peerUserId);

        LocalDateTime muteUntil = req.muted ? MUTE_FOREVER : null;
        boolean ok = singleChatMemberService.update(new LambdaUpdateWrapper<SingleChatMemberEntity>()
                .eq(SingleChatMemberEntity::getSingleChatId, singleChatId)
                .eq(SingleChatMemberEntity::getUserId, userId)
                .set(SingleChatMemberEntity::getMuteUntil, muteUntil));
        if (!ok) {
            return Result.fail(ApiCodes.INTERNAL_ERROR, "internal_error");
        }

        return Result.ok(null);
    }

    public record GroupSetRequest(String groupId, boolean muted) {
    }

    @PostMapping("/group/set")
    public Result<Void> setGroup(@RequestBody GroupSetRequest req) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        if (req == null || req.groupId == null || req.groupId.isBlank()) {
            return Result.fail(ApiCodes.BAD_REQUEST, "missing_group_id");
        }

        long groupId;
        try {
            groupId = Long.parseLong(req.groupId.trim());
        } catch (NumberFormatException ignore) {
            return Result.fail(ApiCodes.BAD_REQUEST, "invalid_group_id");
        }
        if (groupId <= 0) {
            return Result.fail(ApiCodes.BAD_REQUEST, "invalid_group_id");
        }

        boolean isMember = groupMemberService.exists(new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, groupId)
                .eq(GroupMemberEntity::getUserId, userId)
                .last("limit 1"));
        if (!isMember) {
            return Result.fail(ApiCodes.FORBIDDEN, "not_group_member");
        }

        LocalDateTime muteUntil = req.muted ? MUTE_FOREVER : null;
        boolean ok = groupMemberService.update(new LambdaUpdateWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, groupId)
                .eq(GroupMemberEntity::getUserId, userId)
                .set(GroupMemberEntity::getMuteUntil, muteUntil));
        if (!ok) {
            return Result.fail(ApiCodes.INTERNAL_ERROR, "internal_error");
        }

        return Result.ok(null);
    }
}

