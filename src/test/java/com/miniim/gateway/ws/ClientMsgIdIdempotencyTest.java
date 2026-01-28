package com.miniim.gateway.ws;

import com.miniim.gateway.config.ClientMsgIdCaffeineProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClientMsgIdIdempotencyTest {

    @Test
    void shouldReturnNullWhenClaimedFirstTime() {
        ClientMsgIdCaffeineProperties props = new ClientMsgIdCaffeineProperties();
        props.setEnabled(true);
        props.setExpireAfterAccessSeconds(1800);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        ClientMsgIdIdempotency idem = new ClientMsgIdIdempotency(props, redis);

        String key = idem.key(1L, "SINGLE_CHAT", "c1");
        ClientMsgIdIdempotency.Claim claim = new ClientMsgIdIdempotency.Claim();
        claim.setServerMsgId("m1");

        String redisKey = "im:idem:client_msg_id:" + key;
        when(ops.setIfAbsent(eq(redisKey), eq("m1"), any(Duration.class))).thenReturn(true);

        assertNull(idem.putIfAbsent(key, claim));
    }

    @Test
    void shouldReturnExistingWhenDuplicated() {
        ClientMsgIdCaffeineProperties props = new ClientMsgIdCaffeineProperties();
        props.setEnabled(true);
        props.setExpireAfterAccessSeconds(1800);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        ClientMsgIdIdempotency idem = new ClientMsgIdIdempotency(props, redis);

        String key = idem.key(1L, "SINGLE_CHAT", "c1");
        ClientMsgIdIdempotency.Claim claim = new ClientMsgIdIdempotency.Claim();
        claim.setServerMsgId("m1");

        String redisKey = "im:idem:client_msg_id:" + key;
        when(ops.setIfAbsent(eq(redisKey), eq("m1"), any(Duration.class))).thenReturn(false);
        when(ops.get(eq(redisKey))).thenReturn("m0");

        ClientMsgIdIdempotency.Claim existed = idem.putIfAbsent(key, claim);
        assertEquals("m0", existed.getServerMsgId());
    }

    @Test
    void shouldShortCircuitWhenDisabled() {
        ClientMsgIdCaffeineProperties props = new ClientMsgIdCaffeineProperties();
        props.setEnabled(false);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ClientMsgIdIdempotency idem = new ClientMsgIdIdempotency(props, redis);

        String key = idem.key(1L, "SINGLE_CHAT", "c1");
        ClientMsgIdIdempotency.Claim claim = new ClientMsgIdIdempotency.Claim();
        claim.setServerMsgId("m1");

        assertNull(idem.putIfAbsent(key, claim));
    }
}

