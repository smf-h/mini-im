package com.miniim.domain.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class GroupConversationDto {

    private Long groupId;

    private String name;

    /** 会话更新时间（用于排序/游标） */
    private LocalDateTime updatedAt;

    /** 总未读数（不含自己发出的） */
    private Long unreadCount;

    /** @我/回复我 未读数（稀疏索引表计算） */
    private Long mentionUnreadCount;

    /** 最后一条消息（无消息时为 null） */
    private LastMessageDto lastMessage;

    @Data
    public static class LastMessageDto {
        private String serverMsgId;
        private Long fromUserId;
        private String content;
        private LocalDateTime createdAt;
    }
}

