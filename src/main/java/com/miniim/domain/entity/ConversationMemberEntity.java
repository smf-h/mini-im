package com.miniim.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.miniim.domain.enums.MemberRole;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_conversation_member")
public class ConversationMemberEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long conversationId;

    private Long userId;

    /** 成员角色：见 {@link MemberRole}（数据库仍存数字）。 */
    private MemberRole role;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime joinAt;

    private LocalDateTime muteUntil;

    private Long lastDeliveredMsgId;

    private Long lastReadMsgId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
