package com.miniim.auth.service;

import com.miniim.auth.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceSessionVersionTest {

    @Test
    void issueAccessToken_ShouldCarrySessionVersionClaim() {
        AuthProperties props = new AuthProperties(
                "mini-im",
                "change-me-please-change-me-please-change-me",
                1800,
                2592000
        );
        JwtService jwt = new JwtService(props);

        String token = jwt.issueAccessToken(123L, 7L);
        Jws<Claims> jws = jwt.parseAccessToken(token);
        assertEquals(123L, jwt.getUserId(jws.getPayload()));
        assertEquals(7L, jwt.getSessionVersion(jws.getPayload()));
    }
}

