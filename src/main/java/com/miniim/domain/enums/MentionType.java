package com.miniim.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 群聊重要消息索引类型（对应表字段：t_message_mention.mention_type）。
 *
 * <p>数字与类型映射：</p>
 * <ul>
 *   <li>1 = @用户（MENTION）</li>
 *   <li>2 = 回复（REPLY）</li>
 *   <li>3 = @全体（AT_ALL，仅作展示，不强提醒）</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum MentionType {

    MENTION(1, "mention"),

    REPLY(2, "reply"),

    AT_ALL(3, "at_all");

    @EnumValue
    private final Integer code;

    private final String desc;
}

