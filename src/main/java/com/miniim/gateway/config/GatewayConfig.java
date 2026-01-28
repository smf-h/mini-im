package com.miniim.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        GatewayProperties.class,
        WsBackpressureProperties.class,
        WsAckBatchProperties.class,
        WsResendProperties.class,
        WsSingleChatUpdatedAtDebounceProperties.class,
        WsGroupUpdatedAtDebounceProperties.class
})
public class GatewayConfig {
}
