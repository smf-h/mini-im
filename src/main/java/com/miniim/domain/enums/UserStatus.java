package com.miniim.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 用户状态（对应表字段：t_user.status）。
 *
 * <p>数字与类型映射：</p>
 * <ul>
 *   <li>1 = 正常（ACTIVE）</li>
 *   <li>0 = 禁用（DISABLED）</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum UserStatus {

    /** 1 = active */
    ACTIVE(1, "active"),

    /** 0 = disabled */
    DISABLED(0, "disabled");

    @EnumValue
    private final Integer code;

    private final String desc;
}
