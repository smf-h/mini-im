package com.miniim.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.miniim.domain.enums.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Data
@TableName("t_user")
public class UserEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private String username;

    private String passwordHash;

    private String nickname;

    private String avatarUrl;

    /** FriendCode：不可枚举的加好友码。 */
    private String friendCode;

    /** FriendCode 最近一次生成/重置时间（用于限频）。 */
    private LocalDateTime friendCodeUpdatedAt;

    /** 用户状态：见 {@link UserStatus}（数据库仍存数字）。 */
    private UserStatus status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
