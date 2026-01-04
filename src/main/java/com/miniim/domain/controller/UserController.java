package com.miniim.domain.controller;

import com.miniim.auth.web.AuthContext;
import com.miniim.common.api.ApiCodes;
import com.miniim.common.api.Result;
import com.miniim.domain.entity.UserEntity;
import com.miniim.domain.mapper.UserMapper;
import com.miniim.domain.service.CodeService;
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
    private final CodeService codeService;

    public record UserBasicDto(
            Long id,
            String username,
            String nickname
    ) {
    }

    public record UserProfileDto(
            Long id,
            String username,
            String nickname,
            String avatarUrl,
            Integer status,
            String friendCode
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

    @GetMapping("/profile")
    public Result<UserProfileDto> profile(@RequestParam Long userId) {
        Long requester = AuthContext.getUserId();
        if (requester == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        if (userId == null || userId <= 0) {
            return Result.fail(ApiCodes.BAD_REQUEST, "bad_user_id");
        }

        UserEntity u = userMapper.selectById(userId);
        if (u == null) {
            return Result.fail(ApiCodes.NOT_FOUND, "not_found");
        }

        // 公开个人主页：可见 friendCode；如为空则懒生成（避免历史用户无 code）。
        if (u.getFriendCode() == null || u.getFriendCode().isBlank()) {
            try {
                u = codeService.ensureFriendCode(userId);
            } catch (Exception ignore) {
                // ignore
            }
        }

        Integer status = u.getStatus() == null ? null : u.getStatus().getCode();
        return Result.ok(new UserProfileDto(
                u.getId(),
                u.getUsername(),
                u.getNickname(),
                u.getAvatarUrl(),
                status,
                u.getFriendCode()
        ));
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
