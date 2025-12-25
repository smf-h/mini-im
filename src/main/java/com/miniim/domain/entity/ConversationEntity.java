package com.miniim.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.miniim.domain.enums.ConversationType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_conversation")
public class ConversationEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 会话类型：见 {@link ConversationType}（数据库仍存数字）。 */
    private ConversationType type;

    private String title;

    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
