package com.miniim.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.group-chat.strategy")
public class GroupChatStrategyProperties {

    /**
     * 策略模式：
     * <ul>
     *   <li>auto：按阈值自动选择</li>
     *   <li>push：固定策略1（推消息体）</li>
     *   <li>notify：固定策略2（通知后拉取）</li>
     *   <li>none：不推（仅落库，靠拉取/补发兜底）</li>
     * </ul>
     */
    private String mode = "auto";

    /** 群成员数达到该阈值时优先使用策略2。 */
    private int groupSizeThreshold = 2000;

    /** 在线用户数达到该阈值时优先使用策略2。 */
    private int onlineUserThreshold = 500;

    /** 在线用户数达到该阈值时不推通知（兜底降级）。 */
    private int notifyMaxOnlineUser = 2000;

    /** 群成员数达到该阈值时直接不推通知（跳过路由批查）。 */
    private int hugeGroupNoNotifySize = 10000;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public int getGroupSizeThreshold() {
        return groupSizeThreshold;
    }

    public void setGroupSizeThreshold(int groupSizeThreshold) {
        this.groupSizeThreshold = groupSizeThreshold;
    }

    public int getOnlineUserThreshold() {
        return onlineUserThreshold;
    }

    public void setOnlineUserThreshold(int onlineUserThreshold) {
        this.onlineUserThreshold = onlineUserThreshold;
    }

    public int getNotifyMaxOnlineUser() {
        return notifyMaxOnlineUser;
    }

    public void setNotifyMaxOnlineUser(int notifyMaxOnlineUser) {
        this.notifyMaxOnlineUser = notifyMaxOnlineUser;
    }

    public int getHugeGroupNoNotifySize() {
        return hugeGroupNoNotifySize;
    }

    public void setHugeGroupNoNotifySize(int hugeGroupNoNotifySize) {
        this.hugeGroupNoNotifySize = hugeGroupNoNotifySize;
    }
}

