package com.miniim.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.miniim.domain.enums.AckType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_message_ack")
public class MessageAckEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long messageId;

    private Long userId;

    private String deviceId;

    /** 回执类型：见 {@link AckType}（数据库仍存数字）。 */
    private AckType ackType;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime ackAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
