package com.miniim.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.gateway.group-fanout")
public class GroupFanoutProperties {

    /** 是否启用“按实例分组批量 PUSH”。 */
    private boolean enabled = true;

    /** 批量 publish 的 userId 数量上限（每条消息携带的 userIds 数）。 */
    private int batchSize = 500;

    /** Redis 批量路由查询失败时是否降级（fail-open）。 */
    private boolean failOpen = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public boolean isFailOpen() {
        return failOpen;
    }

    public void setFailOpen(boolean failOpen) {
        this.failOpen = failOpen;
    }
}

