package com.miniim.domain.cache;

import com.miniim.common.cache.CacheProperties;
import com.miniim.common.cache.RedisJsonCache;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FriendIdsCacheTest {

    @Test
    void get_ShouldReturnNull_WhenCacheDisabled() {
        CacheProperties props = new CacheProperties();
        props.setEnabled(false);
        FriendIdsCache cache = new FriendIdsCache(props, mock(RedisJsonCache.class));
        assertNull(cache.get(1));
    }

    @Test
    void get_ShouldReturnEmptySet_WhenCachedEmpty() {
        CacheProperties props = new CacheProperties();
        RedisJsonCache redisJsonCache = mock(RedisJsonCache.class);
        when(redisJsonCache.get(anyString(), any())).thenReturn(new FriendIdsCache.Value(List.of()));

        FriendIdsCache cache = new FriendIdsCache(props, redisJsonCache);
        assertEquals(Set.of(), cache.get(100));
    }

    @Test
    void get_ShouldParseIds() {
        CacheProperties props = new CacheProperties();
        RedisJsonCache redisJsonCache = mock(RedisJsonCache.class);
        when(redisJsonCache.get(anyString(), any())).thenReturn(new FriendIdsCache.Value(List.of(1L, 2L, 2L, -1L, 0L)));

        FriendIdsCache cache = new FriendIdsCache(props, redisJsonCache);
        assertEquals(Set.of(1L, 2L), cache.get(100));
    }
}

