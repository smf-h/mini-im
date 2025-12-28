package com.miniim.domain.controller;

import com.miniim.auth.web.AuthContext;
import com.miniim.common.api.ApiCodes;
import com.miniim.common.api.Result;
import com.miniim.domain.entity.UserEntity;
import com.miniim.domain.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 用户基础信息：用于前端展示昵称/用户名（例如通知、聊天列表等）。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/user")
public class UserController {

    private final UserMapper userMapper;

    public record UserBasicDto(
            Long id,
            String username,
            String nickname
    ) {
    }

    @GetMapping("/basic")
    public Result<List<UserBasicDto>> basic(@RequestParam String ids) {
        Long requester = AuthContext.getUserId();
        if (requester == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }

        List<Long> parsedIds;
        try {
            parsedIds = parseIds(ids, 100);
        } catch (IllegalArgumentException e) {
            return Result.fail(ApiCodes.BAD_REQUEST, "bad_ids");
        }

        if (parsedIds.isEmpty()) {
            return Result.ok(List.of());
        }

        List<UserEntity> users = userMapper.selectBatchIds(parsedIds);
        List<UserBasicDto> resp = new ArrayList<>(users.size());
        for (UserEntity u : users) {
            resp.add(new UserBasicDto(u.getId(), u.getUsername(), u.getNickname()));
        }
        return Result.ok(resp);
    }

    static List<Long> parseIds(String ids, int max) {
        if (ids == null || ids.isBlank()) {
            return List.of();
        }
        String[] parts = ids.split(",");
        Set<Long> set = new LinkedHashSet<>();
        for (String part : parts) {
            if (part == null) continue;
            String s = part.trim();
            if (s.isEmpty()) continue;
            long v = Long.parseLong(s);
            if (v <= 0) {
                throw new IllegalArgumentException("bad_id");
            }
            set.add(v);
            if (set.size() >= max) {
                break;
            }
        }
        return new ArrayList<>(set);
    }
}

