package com.miniim.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.miniim.domain.enums.ChatType;
import com.miniim.domain.enums.MessageStatus;
import com.miniim.domain.enums.MessageType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_message")
public class MessageEntity {

    public static final String REVOKED_PLACEHOLDER = "已撤回";

    /** msgId */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 聊天类型：见 {@link ChatType}（数据库仍存数字）。 */
    private ChatType chatType;

    private Long singleChatId;

    private Long groupId;

    private Long fromUserId;

    /** single chat target userId; group chat is null */
    private Long toUserId;

    /** 消息内容类型：见 {@link MessageType}（数据库仍存数字）。 */
    private MessageType msgType;

    @JsonIgnore
    private String content;

    /** 消息状态：见 {@link MessageStatus}（数据库仍存数字）。 */
    private MessageStatus status;

    /** client idempotency key */
    private String clientMsgId;

    /**
     * 服务端回传给客户端的消息 id。
     *
      * <p>对应表字段：t_message.server_msg_id（可选）。
      * 通常可直接使用 {@link #id}（msgId）；保留该列主要用于协议层字段命名/兼容。</p>
     */
     private String serverMsgId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @JsonIgnore
    public String getContent() {
        return content;
    }

    @JsonProperty("content")
    public String getContentForJson() {
        if (status == MessageStatus.REVOKED) {
            return REVOKED_PLACEHOLDER;
        }
        return content;
    }
}
