package com.miniim.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum GroupJoinRequestStatus {

    PENDING(1, "pending"),
    ACCEPTED(2, "accepted"),
    REJECTED(3, "rejected"),
    CANCELED(4, "canceled");

    @EnumValue
    private final Integer code;

    private final String desc;

    public static GroupJoinRequestStatus fromString(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase();
        return switch (s) {
            case "pending" -> PENDING;
            case "accepted" -> ACCEPTED;
            case "rejected" -> REJECTED;
            case "canceled", "cancelled" -> CANCELED;
            default -> null;
        };
    }
}

