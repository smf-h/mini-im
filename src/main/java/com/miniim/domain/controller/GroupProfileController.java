package com.miniim.domain.controller;

import com.miniim.auth.web.AuthContext;
import com.miniim.common.api.ApiCodes;
import com.miniim.common.api.Result;
import com.miniim.domain.service.GroupManagementService;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/group")
public class GroupProfileController {

    private final GroupManagementService groupManagementService;

    @GetMapping("/profile/by-id")
    public Result<GroupManagementService.GroupProfile> profileById(@RequestParam Long groupId) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        try {
            return Result.ok(groupManagementService.profileById(userId, groupId == null ? 0 : groupId));
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if ("unauthorized".equals(msg)) return Result.fail(ApiCodes.UNAUTHORIZED, msg);
            if ("not_found".equals(msg)) return Result.fail(ApiCodes.NOT_FOUND, msg);
            return Result.fail(ApiCodes.BAD_REQUEST, msg == null ? "bad_request" : msg);
        }
    }

    @GetMapping("/profile/by-code")
    public Result<GroupManagementService.GroupProfile> profileByCode(@RequestParam String groupCode) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        try {
            return Result.ok(groupManagementService.profileByCode(userId, groupCode));
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if ("unauthorized".equals(msg)) return Result.fail(ApiCodes.UNAUTHORIZED, msg);
            if ("not_found".equals(msg)) return Result.fail(ApiCodes.NOT_FOUND, msg);
            return Result.fail(ApiCodes.BAD_REQUEST, msg == null ? "bad_request" : msg);
        }
    }

    @GetMapping("/member/list")
    public Result<List<GroupManagementService.GroupMember>> memberList(@RequestParam Long groupId) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        try {
            return Result.ok(groupManagementService.memberList(userId, groupId == null ? 0 : groupId));
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if ("unauthorized".equals(msg)) return Result.fail(ApiCodes.UNAUTHORIZED, msg);
            if ("forbidden".equals(msg)) return Result.fail(ApiCodes.FORBIDDEN, msg);
            return Result.fail(ApiCodes.BAD_REQUEST, msg == null ? "bad_request" : msg);
        }
    }

    public record LeaveRequest(@NotNull Long groupId) {
    }

    @PostMapping("/leave")
    public Result<Void> leave(@RequestBody LeaveRequest req) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        if (req == null || req.groupId() == null) {
            return Result.fail(ApiCodes.BAD_REQUEST, "bad_request");
        }
        try {
            groupManagementService.leave(userId, req.groupId());
            return Result.ok(null);
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if ("unauthorized".equals(msg)) return Result.fail(ApiCodes.UNAUTHORIZED, msg);
            if ("forbidden".equals(msg) || "not_member".equals(msg)) return Result.fail(ApiCodes.FORBIDDEN, msg);
            return Result.fail(ApiCodes.BAD_REQUEST, msg == null ? "bad_request" : msg);
        }
    }

    public record KickRequest(
            @NotNull Long groupId,
            @NotNull Long userId
    ) {
    }

    @PostMapping("/member/kick")
    public Result<Void> kick(@RequestBody KickRequest req) {
        Long operatorId = AuthContext.getUserId();
        if (operatorId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        if (req == null) {
            return Result.fail(ApiCodes.BAD_REQUEST, "bad_request");
        }
        try {
            groupManagementService.kick(operatorId, req.groupId() == null ? 0 : req.groupId(), req.userId() == null ? 0 : req.userId());
            return Result.ok(null);
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if ("unauthorized".equals(msg)) return Result.fail(ApiCodes.UNAUTHORIZED, msg);
            if ("forbidden".equals(msg)) return Result.fail(ApiCodes.FORBIDDEN, msg);
            return Result.fail(ApiCodes.BAD_REQUEST, msg == null ? "bad_request" : msg);
        }
    }

    public record SetAdminRequest(
            @NotNull Long groupId,
            @NotNull Long userId,
            boolean admin
    ) {
    }

    @PostMapping("/member/set-admin")
    public Result<Void> setAdmin(@RequestBody SetAdminRequest req) {
        Long operatorId = AuthContext.getUserId();
        if (operatorId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        if (req == null) {
            return Result.fail(ApiCodes.BAD_REQUEST, "bad_request");
        }
        try {
            groupManagementService.setAdmin(operatorId, req.groupId() == null ? 0 : req.groupId(), req.userId() == null ? 0 : req.userId(), req.admin());
            return Result.ok(null);
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if ("unauthorized".equals(msg)) return Result.fail(ApiCodes.UNAUTHORIZED, msg);
            if ("forbidden".equals(msg)) return Result.fail(ApiCodes.FORBIDDEN, msg);
            return Result.fail(ApiCodes.BAD_REQUEST, msg == null ? "bad_request" : msg);
        }
    }

    public record TransferOwnerRequest(
            @NotNull Long groupId,
            @NotNull Long newOwnerUserId
    ) {
    }

    @PostMapping("/owner/transfer")
    public Result<Void> transferOwner(@RequestBody TransferOwnerRequest req) {
        Long operatorId = AuthContext.getUserId();
        if (operatorId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        if (req == null) {
            return Result.fail(ApiCodes.BAD_REQUEST, "bad_request");
        }
        try {
            groupManagementService.transferOwner(operatorId, req.groupId() == null ? 0 : req.groupId(), req.newOwnerUserId() == null ? 0 : req.newOwnerUserId());
            return Result.ok(null);
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if ("unauthorized".equals(msg)) return Result.fail(ApiCodes.UNAUTHORIZED, msg);
            if ("forbidden".equals(msg)) return Result.fail(ApiCodes.FORBIDDEN, msg);
            return Result.fail(ApiCodes.BAD_REQUEST, msg == null ? "bad_request" : msg);
        }
    }

    public record ResetGroupCodeRequest(@NotNull Long groupId) {
    }

    public record MuteMemberRequest(
            @NotNull Long groupId,
            @NotNull Long userId,
            long durationSeconds
    ) {
    }

    @PostMapping("/member/mute")
    public Result<Void> muteMember(@RequestBody MuteMemberRequest req) {
        Long operatorId = AuthContext.getUserId();
        if (operatorId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        if (req == null || req.groupId() == null || req.userId() == null) {
            return Result.fail(ApiCodes.BAD_REQUEST, "bad_request");
        }
        try {
            groupManagementService.muteMember(
                    operatorId,
                    req.groupId() == null ? 0 : req.groupId(),
                    req.userId() == null ? 0 : req.userId(),
                    req.durationSeconds()
            );
            return Result.ok(null);
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if ("unauthorized".equals(msg)) return Result.fail(ApiCodes.UNAUTHORIZED, msg);
            if ("forbidden".equals(msg)) return Result.fail(ApiCodes.FORBIDDEN, msg);
            return Result.fail(ApiCodes.BAD_REQUEST, msg == null ? "bad_request" : msg);
        }
    }

    @PostMapping("/code/reset")
    public Result<GroupManagementService.ResetGroupCodeResult> resetGroupCode(@RequestBody ResetGroupCodeRequest req) {
        Long operatorId = AuthContext.getUserId();
        if (operatorId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        if (req == null || req.groupId() == null) {
            return Result.fail(ApiCodes.BAD_REQUEST, "bad_request");
        }
        try {
            return Result.ok(groupManagementService.resetGroupCode(operatorId, req.groupId()));
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if ("unauthorized".equals(msg)) return Result.fail(ApiCodes.UNAUTHORIZED, msg);
            if ("forbidden".equals(msg)) return Result.fail(ApiCodes.FORBIDDEN, msg);
            if ("cooldown_not_reached".equals(msg)) return Result.fail(ApiCodes.TOO_MANY_REQUESTS, msg);
            if ("not_found".equals(msg)) return Result.fail(ApiCodes.NOT_FOUND, msg);
            return Result.fail(ApiCodes.BAD_REQUEST, msg == null ? "bad_request" : msg);
        }
    }
}
