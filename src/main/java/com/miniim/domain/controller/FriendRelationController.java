package com.miniim.domain.controller;

import com.miniim.auth.web.AuthContext;
import com.miniim.common.api.ApiCodes;
import com.miniim.common.api.Result;
import com.miniim.domain.entity.FriendRelationEntity;
import com.miniim.domain.service.FriendRelationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/friend/relation")
public class FriendRelationController {
    private final FriendRelationService friendRelationService;

    @GetMapping("/list")
    public Result<List<FriendRelationEntity>> list() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        return Result.ok(friendRelationService.listByUserId(userId));
    }

    @GetMapping("/cursor")
    public Result<List<FriendRelationEntity>> cursor(
            @RequestParam(defaultValue = "10") Long limit,
            @RequestParam(required = false) Long lastId
    ) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        List<FriendRelationEntity> list = friendRelationService.cursorByUserId(userId, limit, lastId);
        return Result.ok(list);
    }
}
