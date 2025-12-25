package com.miniim.auth.dto;

public record RefreshResponse(
        long userId,
        String accessToken,
        long accessTokenExpiresInSeconds
) {
}
