package com.miniim.domain.controller;

import com.miniim.common.api.Result;
import com.miniim.domain.entity.FriendRequestEntity;
import com.miniim.domain.service.FriendRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/friend/request")
public class FriendRequestController {
    private final FriendRequestService friendRequestService;
    @GetMapping
    public Result<List<FriendRequestEntity>> list(){
        return Result.ok(friendRequestService.list());
    }
}
