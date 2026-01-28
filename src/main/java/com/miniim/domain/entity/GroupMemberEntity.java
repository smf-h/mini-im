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
@TableName("t_group_member")
public class GroupMemberEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long groupId;

    private Long userId;

    /** 成员角色：见 {@link MemberRole}（数据库仍存数字）。 */
    private MemberRole role;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime joinAt;

    private LocalDateTime muteUntil;

    /** 禁言截止时间：用于控制是否允许发送群消息；为空表示未禁言。 */
    private LocalDateTime speakMuteUntil;

    private Long lastDeliveredMsgId;

    private Long lastReadMsgId;

    /** delivered cursor by msg_seq（会话内序列）。 */
    private Long lastDeliveredMsgSeq;

    /** read cursor by msg_seq（会话内序列）。 */
    private Long lastReadMsgSeq;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
