package com.miniim.domain.cache;

import com.miniim.common.cache.CacheProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroupMemberIdsCacheTest {

    @Test
    void get_ShouldReturnNull_WhenCacheDisabled() {
        CacheProperties props = new CacheProperties();
        props.setEnabled(false);
        GroupMemberIdsCache cache = new GroupMemberIdsCache(props, mock(StringRedisTemplate.class));
        assertNull(cache.get(1));
    }

    @Test
    void get_ShouldParseMemberIds() {
        CacheProperties props = new CacheProperties();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> ops = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(ops);
        when(ops.members(anyString())).thenReturn(Set.of("1", " 2 ", "bad", "", "0", "-1"));

        GroupMemberIdsCache cache = new GroupMemberIdsCache(props, redis);
        assertEquals(Set.of(1L, 2L), cache.get(100));
    }
}

