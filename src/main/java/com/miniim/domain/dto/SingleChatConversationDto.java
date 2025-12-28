package com.miniim.domain.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SingleChatConversationDto {

    private Long singleChatId;

    private Long peerUserId;

    /** 会话更新时间（用于排序/游标） */
    private LocalDateTime updatedAt;

    private Long unreadCount;

    private Long myLastReadMsgId;

    private Long peerLastReadMsgId;

    /** 最后一条消息（无消息时为 null） */
    private LastMessageDto lastMessage;

    @Data
    public static class LastMessageDto {
        private String serverMsgId;
        private Long fromUserId;
        private Long toUserId;
        private String content;
        private LocalDateTime createdAt;
    }
}
