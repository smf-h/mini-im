package com.miniim.domain.controller;

import com.miniim.auth.web.AuthContext;
import com.miniim.common.api.ApiCodes;
import com.miniim.common.api.Result;
import com.miniim.domain.entity.GroupJoinRequestEntity;
import com.miniim.domain.service.GroupJoinRequestService;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/group/join")
public class GroupJoinController {

    private final GroupJoinRequestService groupJoinRequestService;

    public record JoinRequest(
            @NotBlank String groupCode,
            String message
    ) {
    }

    public record JoinResponse(
            String requestId
    ) {
    }

    @PostMapping("/request")
    public Result<JoinResponse> request(@RequestBody JoinRequest req) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        if (req == null) {
            return Result.fail(ApiCodes.BAD_REQUEST, "bad_request");
        }
        try {
            Long id = groupJoinRequestService.requestJoinByCode(userId, req.groupCode(), req.message());
            return Result.ok(new JoinResponse(String.valueOf(id)));
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if ("unauthorized".equals(msg)) return Result.fail(ApiCodes.UNAUTHORIZED, msg);
            if ("forbidden".equals(msg)) return Result.fail(ApiCodes.FORBIDDEN, msg);
            return Result.fail(ApiCodes.BAD_REQUEST, msg == null ? "bad_request" : msg);
        } catch (Exception e) {
            return Result.fail(ApiCodes.INTERNAL_ERROR, "internal_error");
        }
    }

    @GetMapping("/requests")
    public Result<List<GroupJoinRequestEntity>> list(
            @RequestParam Long groupId,
            @RequestParam(defaultValue = "pending") String status,
            @RequestParam(defaultValue = "20") Long limit,
            @RequestParam(required = false) Long lastId
    ) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        if (groupId == null || groupId <= 0) {
            return Result.fail(ApiCodes.BAD_REQUEST, "bad_group_id");
        }
        try {
            return Result.ok(groupJoinRequestService.listForGroup(userId, groupId, status, limit == null ? 20 : limit, lastId));
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if ("unauthorized".equals(msg)) return Result.fail(ApiCodes.UNAUTHORIZED, msg);
            if ("forbidden".equals(msg)) return Result.fail(ApiCodes.FORBIDDEN, msg);
            return Result.fail(ApiCodes.BAD_REQUEST, msg == null ? "bad_request" : msg);
        }
    }

    public record DecideRequest(
            @NotNull Long requestId,
            @NotBlank String action
    ) {
    }

    @PostMapping("/decide")
    public Result<GroupJoinRequestEntity> decide(@RequestBody DecideRequest req) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        if (req == null) {
            return Result.fail(ApiCodes.BAD_REQUEST, "bad_request");
        }
        try {
            return Result.ok(groupJoinRequestService.decide(userId, req.requestId(), req.action()));
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if ("unauthorized".equals(msg)) return Result.fail(ApiCodes.UNAUTHORIZED, msg);
            if ("forbidden".equals(msg)) return Result.fail(ApiCodes.FORBIDDEN, msg);
            if ("not_found".equals(msg)) return Result.fail(ApiCodes.NOT_FOUND, msg);
            return Result.fail(ApiCodes.BAD_REQUEST, msg == null ? "bad_request" : msg);
        } catch (Exception e) {
            return Result.fail(ApiCodes.INTERNAL_ERROR, "internal_error");
        }
    }
}

