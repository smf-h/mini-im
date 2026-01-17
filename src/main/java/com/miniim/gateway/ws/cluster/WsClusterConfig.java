package com.miniim.gateway.ws.cluster;

import com.miniim.gateway.session.WsRouteStore;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@RequiredArgsConstructor
public class WsClusterConfig {

    @Bean
    public RedisMessageListenerContainer wsClusterListenerContainer(
            RedisConnectionFactory connectionFactory,
            WsClusterListener listener,
            WsRouteStore routeStore
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer() {
            @Override
            public boolean isAutoStartup() {
                return false;
            }
        };
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new ChannelTopic(WsClusterBus.topic(routeStore.serverId())));

        // Redis 宕机/不可用时仍允许网关启动：监听器由 WsClusterListenerStarter 负责“容错启动 + 失败重试”。
        container.setRecoveryBackoff(new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
        return container;
    }
}
