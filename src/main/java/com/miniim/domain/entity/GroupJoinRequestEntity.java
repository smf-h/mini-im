package com.miniim.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.miniim.domain.enums.GroupJoinRequestStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_group_join_request")
public class GroupJoinRequestEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long groupId;

    private Long fromUserId;

    private String message;

    private GroupJoinRequestStatus status;

    private Long handledBy;

    private LocalDateTime handledAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

