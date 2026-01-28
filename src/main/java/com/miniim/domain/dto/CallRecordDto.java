package com.miniim.domain.dto;

import com.miniim.domain.enums.CallStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CallRecordDto {
    private Long id;
    private Long callId;
    private Long peerUserId;
    private String direction; // IN/OUT
    private CallStatus status;
    private String failReason;
    private LocalDateTime startedAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime endedAt;
    private Integer durationSeconds;
}

