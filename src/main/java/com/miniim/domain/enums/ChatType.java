package com.miniim.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 聊天类型（对应表字段：t_message.chat_type）。
 *
 * <p>数字与类型映射：</p>
 * <ul>
 *   <li>1 = 单聊（SINGLE）</li>
 *   <li>2 = 群聊（GROUP）</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum ChatType {

    /** 1 = SINGLE_CHAT */
    SINGLE(1, "SINGLE_CHAT"),

    /** 2 = GROUP_CHAT */
    GROUP(2, "GROUP_CHAT"),;

    @EnumValue
    private final Integer code;

    private final String desc;
}
