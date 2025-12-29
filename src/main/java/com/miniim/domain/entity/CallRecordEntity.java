package com.miniim.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.miniim.domain.enums.CallStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_call_record")
public class CallRecordEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long callId;

    private Long singleChatId;

    private Long callerUserId;

    private Long calleeUserId;

    private CallStatus status;

    private String failReason;

    private LocalDateTime startedAt;

    private LocalDateTime acceptedAt;

    private LocalDateTime endedAt;

    private Integer durationSeconds;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

