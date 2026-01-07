package com.miniim.domain.controller;

import com.miniim.auth.web.AuthContext;
import com.miniim.common.api.ApiCodes;
import com.miniim.common.api.Result;
import com.miniim.domain.cache.UserProfileCache;
import com.miniim.domain.entity.UserEntity;
import com.miniim.domain.mapper.UserMapper;
import com.miniim.domain.service.CodeService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
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

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserMapper userMapper;
    private final CodeService codeService;
    private final UserProfileCache userProfileCache;

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

        var cached = new HashMap<>(userProfileCache.getBatch(parsedIds));
        Set<Long> miss = new LinkedHashSet<>();
        for (Long id : parsedIds) {
            if (id == null || id <= 0) continue;
            if (!cached.containsKey(id)) {
                miss.add(id);
            }
        }

        if (!miss.isEmpty()) {
            List<UserEntity> users = userMapper.selectBatchIds(miss);
            for (UserEntity u : users) {
                if (u == null || u.getId() == null) continue;
                Integer status = u.getStatus() == null ? null : u.getStatus().getCode();
                UserProfileCache.Value v = new UserProfileCache.Value(
                        u.getId(),
                        u.getUsername(),
                        u.getNickname(),
                        u.getAvatarUrl(),
                        status,
                        u.getFriendCode(),
                        u.getFriendCodeUpdatedAt()
                );
                userProfileCache.put(u.getId(), v);
                cached.put(u.getId(), v);
            }
        }

        List<UserBasicDto> resp = new ArrayList<>(parsedIds.size());
        for (Long id : parsedIds) {
            if (id == null || id <= 0) continue;
            UserProfileCache.Value v = cached.get(id);
            if (v == null) continue;
            resp.add(new UserBasicDto(v.id(), v.username(), v.nickname()));
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

        UserProfileCache.Value cached = userProfileCache.get(userId);
        if (cached != null && cached.friendCode() != null && !cached.friendCode().isBlank()) {
            return Result.ok(new UserProfileDto(
                    cached.id(),
                    cached.username(),
                    cached.nickname(),
                    cached.avatarUrl(),
                    cached.status(),
                    cached.friendCode()
            ));
        }

        UserEntity u = userMapper.selectById(userId);
        if (u == null) {
            return Result.fail(ApiCodes.NOT_FOUND, "not_found");
        }

        // 公开个人主页：可见 friendCode；如为空则懒生成（避免历史用户无 code）。
        if (u.getFriendCode() == null || u.getFriendCode().isBlank()) {
            try {
                u = codeService.ensureFriendCode(userId);
            } catch (Exception e) {
                log.debug("ensure friendCode failed: userId={}, err={}", userId, e.toString());
            }
        }

        Integer status = u.getStatus() == null ? null : u.getStatus().getCode();
        userProfileCache.put(userId, new UserProfileCache.Value(
                u.getId(),
                u.getUsername(),
                u.getNickname(),
                u.getAvatarUrl(),
                status,
                u.getFriendCode(),
                u.getFriendCodeUpdatedAt()
        ));

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
