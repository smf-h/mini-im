package com.miniim.domain.dto.single;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SendMessageRequest {
    @NotNull
    private Long toUserId;
    @NotBlank
    private String content;
    @NotBlank
    private String clientMsgId;
}
