package com.miniim.common.web;

import com.miniim.common.api.ApiCodes;
import com.miniim.common.api.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void handleNoResourceFound_ShouldReturn404ResultEnvelope() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<Result<Void>> resp = handler.handleNoResourceFound(new NoResourceFoundException(HttpMethod.POST, "user/user/login"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().ok()).isFalse();
        assertThat(resp.getBody().code()).isEqualTo(ApiCodes.NOT_FOUND);
    }
}

