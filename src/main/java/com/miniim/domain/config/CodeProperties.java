package com.miniim.domain.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.code")
public class CodeProperties {

    /** FriendCode 长度（默认 8）。 */
    private int friendCodeLength = 8;

    /** GroupCode 长度（默认 8）。 */
    private int groupCodeLength = 8;

    /** 重置冷却时间（秒，默认 86400=24h）。 */
    private long resetCooldownSeconds = 86400;

    public int getFriendCodeLength() {
        return friendCodeLength;
    }

    public void setFriendCodeLength(int friendCodeLength) {
        this.friendCodeLength = friendCodeLength;
    }

    public int getGroupCodeLength() {
        return groupCodeLength;
    }

    public void setGroupCodeLength(int groupCodeLength) {
        this.groupCodeLength = groupCodeLength;
    }

    public long getResetCooldownSeconds() {
        return resetCooldownSeconds;
    }

    public void setResetCooldownSeconds(long resetCooldownSeconds) {
        this.resetCooldownSeconds = resetCooldownSeconds;
    }
}

