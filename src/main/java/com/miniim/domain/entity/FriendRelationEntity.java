package com.miniim.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_friend_relation")
public class FriendRelationEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 好友对：user1Id=min(A,B) */
    private Long user1Id;

    /** 好友对：user2Id=max(A,B) */
    private Long user2Id;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
