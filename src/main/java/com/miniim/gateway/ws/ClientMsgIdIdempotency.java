package com.miniim.gateway.ws;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.miniim.gateway.config.ClientMsgIdCaffeineProperties;
import lombok.Data;
import lombok.Getter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 基于 Caffeine 的客户端请求幂等：用 (fromUserId + clientMsgId) 作为 key。
 *
 * <p>用于处理客户端重试：避免重复落库/重复投递。</p>
 */
@Component
@EnableConfigurationProperties(ClientMsgIdCaffeineProperties.class)
public class ClientMsgIdIdempotency {
    @Getter
    private final ClientMsgIdCaffeineProperties props;
    @Data
    public static class Claim{
        String serverMsgId;
    }
    private final Cache<String, Claim> cache;
    public ClientMsgIdIdempotency(ClientMsgIdCaffeineProperties props) {
        this.props = props;
        this.cache = Caffeine.newBuilder()
                .initialCapacity(Math.max(1, props.getInitialCapacity()))
                .maximumSize(Math.max(1, props.getMaximumSize()))
                .expireAfterAccess(Duration.ofSeconds(Math.max(1, props.getExpireAfterAccessSeconds())))
                .build();
    }
    public String key(String fromUserId,String clientMsgId){
        return fromUserId + "-" + clientMsgId;
    }
    public Claim get(String key) {
        return cache.getIfPresent(key);
    }
    public void put(String key, Claim claim){
        cache.put(key, claim);
    }
    public Claim putIfAbsent(String key,Claim claim){
        return cache.asMap().putIfAbsent(key, claim);
    }
    public void remove(String key){
        cache.invalidate(key);
    }

}
