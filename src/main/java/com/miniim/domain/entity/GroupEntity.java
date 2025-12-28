package com.miniim.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_group")
public class GroupEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    private String avatarUrl;

    /** GroupCode：不可枚举的群码（用于申请入群）。 */
    private String groupCode;

    /** GroupCode 最近一次生成/重置时间（用于限频）。 */
    private LocalDateTime groupCodeUpdatedAt;

    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
