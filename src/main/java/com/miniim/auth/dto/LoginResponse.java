package com.miniim.auth.dto;

public record LoginResponse(
        long userId,
        String accessToken,
        String refreshToken,
        long accessTokenExpiresInSeconds,
        long refreshTokenExpiresInSeconds
) {
}
