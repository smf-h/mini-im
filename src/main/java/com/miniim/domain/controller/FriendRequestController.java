package com.miniim.domain.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.miniim.auth.web.AuthContext;
import com.miniim.common.api.Result;
import com.miniim.common.api.ApiCodes;
import com.miniim.domain.entity.FriendRequestEntity;
import com.miniim.domain.service.FriendRequestService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/friend/request")
public class FriendRequestController {
    private final FriendRequestService friendRequestService;

    /**
     * 游标分页：按 id 倒序返回。
     *
     * box:
     * - inbox: 我收到（to_user_id = me）
     * - outbox: 我发出（from_user_id = me）
     * - all: 收+发（默认）
     */
    @GetMapping("/cursor")
    public Result<List<FriendRequestEntity>> cursor(
            @RequestParam(defaultValue = "all") String box,
            @RequestParam(defaultValue = "20") Long limit,
            @RequestParam(required = false) Long lastId
    ) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }

        try {
            return Result.ok(friendRequestService.cursorByUserId(userId, box, limit, lastId));
        } catch (IllegalArgumentException e) {
            return Result.fail(ApiCodes.BAD_REQUEST, "bad_box");
        }
    }

    /**
     * 普通分页：按 pageNo/pageSize 返回 Page 对象（包含 records/total 等）。
     *
     * box 同 cursor。
     */
    @GetMapping("/list")
    public Result<Page<FriendRequestEntity>> list(
            @RequestParam(defaultValue = "all") String box,
            @RequestParam(defaultValue = "1") Long pageNo,
            @RequestParam(defaultValue = "20") Long pageSize
    ) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }

        try {
            return Result.ok(friendRequestService.pageByUserId(userId, box, pageNo, pageSize));
        } catch (IllegalArgumentException e) {
            return Result.fail(ApiCodes.BAD_REQUEST, "bad_box");
        }
    }

    public record DecideRequest(
            @NotNull Long requestId,
            @NotBlank String action
    ) {
    }

    public record DecideResponse(
            Long singleChatId
    ) {
    }

    @PostMapping("/decide")
    public Result<DecideResponse> decide(@RequestBody DecideRequest request) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }

        try {
            Long singleChatId = friendRequestService.decide(userId, request.requestId(), request.action());
            return Result.ok(new DecideResponse(singleChatId));
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if ("unauthorized".equals(msg)) return Result.fail(ApiCodes.UNAUTHORIZED, msg);
            if ("forbidden".equals(msg)) return Result.fail(ApiCodes.FORBIDDEN, msg);
            if ("not_found".equals(msg)) return Result.fail(ApiCodes.NOT_FOUND, msg);
            return Result.fail(ApiCodes.BAD_REQUEST, msg == null ? "bad_request" : msg);
        }
    }
}
