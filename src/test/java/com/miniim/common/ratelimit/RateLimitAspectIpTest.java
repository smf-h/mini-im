package com.miniim.common.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RateLimitAspectIpTest {

    @Test
    void resolveIp_ShouldPreferForwardedHeaders_WhenEnabled() {
        RateLimitProperties props = new RateLimitProperties();
        props.setTrustForwardedHeaders(true);
        RateLimitAspect aspect = new RateLimitAspect(props, mock(StringRedisTemplate.class));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");
        req.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8");
        assertEquals("1.2.3.4", aspect.resolveIp(req));
    }

    @Test
    void resolveIp_ShouldFallbackToRemoteAddr_WhenForwardedMissing() {
        RateLimitProperties props = new RateLimitProperties();
        props.setTrustForwardedHeaders(true);
        RateLimitAspect aspect = new RateLimitAspect(props, mock(StringRedisTemplate.class));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.2");
        assertEquals("10.0.0.2", aspect.resolveIp(req));
    }
}

