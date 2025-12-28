package com.miniim.domain.controller;

import com.miniim.auth.web.AuthContext;
import com.miniim.common.api.ApiCodes;
import com.miniim.common.api.Result;
import com.miniim.domain.entity.UserEntity;
import com.miniim.domain.mapper.UserMapper;
import com.miniim.domain.service.CodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RequiredArgsConstructor
@RestController
@RequestMapping("/me")
public class MeController {

    private final UserMapper userMapper;
    private final CodeService codeService;

    public record MeProfileDto(
            Long id,
            String username,
            String nickname,
            String avatarUrl,
            Integer status,
            String friendCode,
            LocalDateTime friendCodeUpdatedAt,
            LocalDateTime friendCodeNextResetAt
    ) {
    }

    public record ResetFriendCodeResponse(
            String friendCode,
            LocalDateTime friendCodeUpdatedAt,
            LocalDateTime friendCodeNextResetAt
    ) {
    }

    @GetMapping("/profile")
    public Result<MeProfileDto> profile() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }

        UserEntity u = userMapper.selectById(userId);
        if (u == null) {
            return Result.fail(ApiCodes.NOT_FOUND, "not_found");
        }

        if (u.getFriendCode() == null || u.getFriendCode().isBlank()) {
            u = codeService.ensureFriendCode(userId);
        }

        Integer status = u.getStatus() == null ? null : u.getStatus().getCode();
        return Result.ok(new MeProfileDto(
                u.getId(),
                u.getUsername(),
                u.getNickname(),
                u.getAvatarUrl(),
                status,
                u.getFriendCode(),
                u.getFriendCodeUpdatedAt(),
                codeService.nextResetAt(u.getFriendCodeUpdatedAt())
        ));
    }

    @PostMapping("/friend-code/reset")
    public Result<ResetFriendCodeResponse> resetFriendCode() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        try {
            String code = codeService.resetFriendCode(userId);
            UserEntity u = userMapper.selectById(userId);
            LocalDateTime updatedAt = u == null ? null : u.getFriendCodeUpdatedAt();
            return Result.ok(new ResetFriendCodeResponse(code, updatedAt, codeService.nextResetAt(updatedAt)));
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if ("cooldown_not_reached".equals(msg)) {
                return Result.fail(ApiCodes.TOO_MANY_REQUESTS, msg);
            }
            if ("not_found".equals(msg)) {
                return Result.fail(ApiCodes.NOT_FOUND, msg);
            }
            return Result.fail(ApiCodes.BAD_REQUEST, msg == null ? "bad_request" : msg);
        } catch (Exception e) {
            return Result.fail(ApiCodes.INTERNAL_ERROR, "internal_error");
        }
    }
}

