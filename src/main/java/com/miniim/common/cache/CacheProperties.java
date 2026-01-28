package com.miniim.common.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.cache")
public class CacheProperties {

    private boolean enabled = true;

    private long userProfileTtlSeconds = 1800;
    private long friendIdsTtlSeconds = 1800;
    private long groupBaseTtlSeconds = 600;
    private long groupMemberIdsTtlSeconds = 600;
    private long singleChatPairTtlSeconds = 604800;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getUserProfileTtlSeconds() {
        return userProfileTtlSeconds;
    }

    public void setUserProfileTtlSeconds(long userProfileTtlSeconds) {
        this.userProfileTtlSeconds = userProfileTtlSeconds;
    }

    public long getFriendIdsTtlSeconds() {
        return friendIdsTtlSeconds;
    }

    public void setFriendIdsTtlSeconds(long friendIdsTtlSeconds) {
        this.friendIdsTtlSeconds = friendIdsTtlSeconds;
    }

    public long getGroupBaseTtlSeconds() {
        return groupBaseTtlSeconds;
    }

    public void setGroupBaseTtlSeconds(long groupBaseTtlSeconds) {
        this.groupBaseTtlSeconds = groupBaseTtlSeconds;
    }

    public long getGroupMemberIdsTtlSeconds() {
        return groupMemberIdsTtlSeconds;
    }

    public void setGroupMemberIdsTtlSeconds(long groupMemberIdsTtlSeconds) {
        this.groupMemberIdsTtlSeconds = groupMemberIdsTtlSeconds;
    }

    public long getSingleChatPairTtlSeconds() {
        return singleChatPairTtlSeconds;
    }

    public void setSingleChatPairTtlSeconds(long singleChatPairTtlSeconds) {
        this.singleChatPairTtlSeconds = singleChatPairTtlSeconds;
    }
}
