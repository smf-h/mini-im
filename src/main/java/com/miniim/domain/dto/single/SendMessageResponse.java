package com.miniim.domain.dto.single;

import lombok.Data;

@Data
public class SendMessageResponse {
    private String serverMsgId;
    private String status; // SAVED | DELIVERED
    private Long ts;       // epoch millis
}
