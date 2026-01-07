package com.miniim.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.caffeine.client-msg-id")
public class ClientMsgIdCaffeineProperties {

    /** 是否启用客户端请求幂等缓存。 */
    private boolean enabled = true;

    /** Caffeine 初始容量。 */
    private int initialCapacity = 100;

    /** Caffeine 最大条目数。 */
    private long maximumSize = 1000;

    /** 过期（秒）。 */
    private long expireAfterAccessSeconds = 1800;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getInitialCapacity() {
        return initialCapacity;
    }

    public void setInitialCapacity(int initialCapacity) {
        this.initialCapacity = initialCapacity;
    }

    public long getMaximumSize() {
        return maximumSize;
    }

    public void setMaximumSize(long maximumSize) {
        this.maximumSize = maximumSize;
    }

    public long getExpireAfterAccessSeconds() {
        return expireAfterAccessSeconds;
    }

    public void setExpireAfterAccessSeconds(long expireAfterAccessSeconds) {
        this.expireAfterAccessSeconds = expireAfterAccessSeconds;
    }
}
