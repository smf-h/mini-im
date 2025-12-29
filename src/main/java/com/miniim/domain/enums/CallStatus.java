package com.miniim.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CallStatus {

    RINGING(1, "ringing"),

    ACCEPTED(2, "accepted"),

    REJECTED(3, "rejected"),

    CANCELED(4, "canceled"),

    ENDED(5, "ended"),

    MISSED(6, "missed"),

    FAILED(7, "failed");

    @EnumValue
    private final Integer code;

    private final String desc;
}

