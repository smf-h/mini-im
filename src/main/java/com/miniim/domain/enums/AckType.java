package com.miniim.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 消息回执类型（对应表字段：t_message_ack.ack_type）。
 *
 * <p>数字与类型映射：</p>
 * <ul>
 *   <li>1 = 已保存（SAVED）</li>
 *   <li>2 = 已投递（DELIVERED）</li>
 *   <li>3 = 已读（READ）</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum AckType {

    /** 1 = saved */
    SAVED(1, "saved"),

    /** 2 = delivered */
    DELIVERED(2, "delivered"),

    /** 3 = read */
    READ(3, "read");


    @EnumValue
    private final Integer code;

    private final String desc;
}

