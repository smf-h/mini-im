package com.miniim.domain.dto.single;

import lombok.Data;

@Data
public class MessageDTO {
    private Long id;
    private Long fromUserId;
    private Long toUserId;
    private String msgType;
    private String content;
    private Long ts;
}
