package com.miniim.domain.controller;

import com.miniim.auth.web.AuthContext;
import com.miniim.common.api.ApiCodes;
import com.miniim.common.api.Result;
import com.miniim.common.ratelimit.RateLimit;
import com.miniim.common.ratelimit.RateLimitKey;
import com.miniim.domain.entity.GroupEntity;
import com.miniim.domain.entity.GroupMemberEntity;
import com.miniim.domain.service.GroupService;
import com.miniim.domain.service.GroupMemberService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/group")
public class GroupController {

    private final GroupService groupService;
    private final GroupMemberService groupMemberService;

    @PostMapping("/create")
    @RateLimit(name = "group_create", windowSeconds = 60, max = 1, key = RateLimitKey.USER)
    public Result<CreateGroupResponse> create(@RequestBody CreateGroupRequest req) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        if (req == null || req.name == null || req.name.isBlank()) {
            return Result.fail(ApiCodes.BAD_REQUEST, "missing_group_name");
        }
        String name = req.name.trim();
        if (name.length() > 64) {
            return Result.fail(ApiCodes.BAD_REQUEST, "group_name_too_long");
        }

        List<Long> members = req.memberUserIds == null ? List.of() : req.memberUserIds;
        if (!members.isEmpty()) {
            return Result.fail(ApiCodes.BAD_REQUEST, "group_member_invite_not_supported");
        }

        GroupEntity group;
        try {
            group = groupService.createGroup(userId, name, members);
        } catch (IllegalArgumentException e) {
            return Result.fail(ApiCodes.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return Result.fail(ApiCodes.INTERNAL_ERROR, "internal_error");
        }

        CreateGroupResponse resp = new CreateGroupResponse();
        resp.groupId = String.valueOf(group.getId());
        return Result.ok(resp);
    }

    @GetMapping("/basic")
    public Result<List<GroupBasicDto>> basic(@RequestParam String ids) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        if (ids == null || ids.isBlank()) {
            return Result.ok(List.of());
        }
        String[] parts = ids.split(",");
        List<Long> parsed = new ArrayList<>();
        for (String p : parts) {
            String s = p == null ? "" : p.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                long id = Long.parseLong(s);
                if (id > 0) {
                    parsed.add(id);
                }
            } catch (NumberFormatException ignore) {
                // ids 参数中包含非数字项：跳过该项
            }
        }
        if (parsed.isEmpty()) {
            return Result.ok(List.of());
        }

        List<GroupMemberEntity> members = groupMemberService.list(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getUserId, userId)
                .in(GroupMemberEntity::getGroupId, parsed));
        List<Long> allowed = new ArrayList<>();
        if (members != null) {
            for (GroupMemberEntity m : members) {
                if (m == null || m.getGroupId() == null) {
                    continue;
                }
                allowed.add(m.getGroupId());
            }
        }
        if (allowed.isEmpty()) {
            return Result.ok(List.of());
        }

        List<GroupEntity> list = groupService.listByIds(allowed);
        List<GroupBasicDto> out = new ArrayList<>();
        if (list != null) {
            for (GroupEntity g : list) {
                if (g == null || g.getId() == null) {
                    continue;
                }
                GroupBasicDto dto = new GroupBasicDto();
                dto.id = String.valueOf(g.getId());
                dto.name = g.getName();
                dto.avatarUrl = g.getAvatarUrl();
                out.add(dto);
            }
        }
        return Result.ok(out);
    }

    @Data
    public static class CreateGroupRequest {
        private String name;
        private List<Long> memberUserIds;
    }

    @Data
    public static class CreateGroupResponse {
        private String groupId;
    }

    @Data
    public static class GroupBasicDto {
        private String id;
        private String name;
        private String avatarUrl;
    }
}
