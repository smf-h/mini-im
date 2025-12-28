package com.miniim.domain.dto;

import lombok.Data;

@Data
public class SingleChatMemberStateDto {

    private Long singleChatId;

    private Long peerUserId;

    private Long myLastReadMsgId;

    private Long peerLastReadMsgId;
}

