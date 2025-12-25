package com.miniim.auth.dto;

public record VerifyResponse(
        boolean ok,
        Long userId,
        String reason
) {
    public static VerifyResponse ok(long userId) {
        return new VerifyResponse(true, userId, null);
    }

    public static VerifyResponse fail(String reason) {
        return new VerifyResponse(false, null, reason);
    }
}
