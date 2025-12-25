package com.miniim.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 消息内容类型（对应表字段：t_message.msg_type）。
 *
 * <p>数字与类型映射：</p>
 * <ul>
 *   <li>1 = 文本（TEXT）</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum MessageType {

    /** 1 = text */
    TEXT(1, "text");

    @EnumValue
    private final Integer code;

    private final String desc;

    /**
     * 将来自协议层/前端的字符串类型（如 "TEXT" / "text"）转换为枚举。
     * 无法识别时返回 null。
     */
    public static MessageType fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        for (MessageType t : values()) {
            if (t.name().equalsIgnoreCase(v) || t.desc.equalsIgnoreCase(v)) {
                return t;
            }
        }
        return null;
    }
}
