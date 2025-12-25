package com.miniim.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 成员角色（对应表字段：t_group_member.role / t_conversation_member.role）。
 *
 * <p>数字与类型映射：</p>
 * <ul>
 *   <li>1 = 群主/会话 Owner（OWNER）</li>
 *   <li>2 = 管理员 Admin（ADMIN）</li>
 *   <li>3 = 普通成员 Member（MEMBER）</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum MemberRole {

    /** 1 = owner */
    OWNER(1, "owner"),

    /** 2 = admin */
    ADMIN(2, "admin"),

    /** 3 = member */
    MEMBER(3, "member");

    @EnumValue
    private final Integer code;

    private final String desc;
}
