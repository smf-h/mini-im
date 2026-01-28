package com.miniim.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.miniim.domain.enums.FriendRequestStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_friend_request")
public class FriendRequestEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long fromUserId;

    private Long toUserId;

    /** 申请验证信息 */
    private String content;

    /** 申请状态：见 {@link FriendRequestStatus}（数据库仍存数字）。 */
    private FriendRequestStatus status;

    /** 处理时间（同意/拒绝/取消/过期） */
    private LocalDateTime handledAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
