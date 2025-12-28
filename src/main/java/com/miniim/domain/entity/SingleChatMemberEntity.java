package com.miniim.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_single_chat_member")
public class SingleChatMemberEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long singleChatId;

    private Long userId;

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

