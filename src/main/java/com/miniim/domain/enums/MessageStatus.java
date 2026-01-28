package com.miniim.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 消息状态（对应表字段：t_message.status）。
 *
 * <p>数字与类型映射：</p>
 * <ul>
 *   <li>0 = 已发送（SENT）</li>
 *   <li>1 = 已保存（SAVED）</li>
 *   <li>2 = 已投递（DELIVERED）</li>
 *   <li>3 = 已读（READ）</li>
 *   <li>4 = 已撤回（REVOKED）</li>
 *   <li>5 = 已收到（RECEIVED）</li>
 *   <li>6 = 已丢弃（DROPPED）</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum MessageStatus {

    /** 0 = sent */
    SENT(0, "sent"),
    /** 1 = saved */
    SAVED(1, "saved"),

    /** 2 = delivered */
    DELIVERED(2, "delivered"),

    /** 3 = read */
    READ(3, "read"),

    /** 4 = revoked */
    REVOKED(4, "revoked"),

    RECEIVED(5, "received"),
    DROPPED(6, "dropped");

    @EnumValue
    private final Integer code;

    private final String desc;
}
