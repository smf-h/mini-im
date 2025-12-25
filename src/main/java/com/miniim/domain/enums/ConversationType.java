package com.miniim.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 会话类型（对应表字段：t_conversation.type）。
 *
 * <p>数字与类型映射：</p>
 * <ul>
 *   <li>1 = 单聊（SINGLE）</li>
 *   <li>2 = 群聊（GROUP）</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum ConversationType {

    /** 1 = 单聊 */
    SINGLE(1, "single"),

    /** 2 = 群聊 */
    GROUP(2, "group");

    @EnumValue
    private final Integer code;

    private final String desc;
}
