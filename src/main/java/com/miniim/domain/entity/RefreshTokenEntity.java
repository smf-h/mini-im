package com.miniim.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_refresh_token")
public class RefreshTokenEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private String deviceId;

    /** sha256 hex */
    private String tokenHash;

    /** refresh token id */
    private String jti;

    private LocalDateTime issuedAt;

    private LocalDateTime expiresAt;

    private LocalDateTime revokedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
