package com.miniim.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.miniim.domain.enums.MentionType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_message_mention")
public class MessageMentionEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long groupId;

    private Long messageId;

    private Long mentionedUserId;

    private MentionType mentionType;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

