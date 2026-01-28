package com.miniim.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum FriendRequestStatus {

    /** 0 = pending */
    PENDING(0, "pending"),

    /** 1 = accepted */
    ACCEPTED(1, "accepted"),

    /** 2 = rejected */
    REJECTED(2, "rejected"),

    /** 3 = canceled */
    CANCELED(3, "canceled"),

    /** 4 = expired */
    EXPIRED(4, "expired");

    @EnumValue
    private final Integer code;

    private final String desc;
}
