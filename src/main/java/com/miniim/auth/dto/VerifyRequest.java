package com.miniim.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyRequest(
        @NotBlank String accessToken
) {
}
