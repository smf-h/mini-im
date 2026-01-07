package com.miniim.domain.service;

import org.springframework.stereotype.Component;

/**
 * 朋友圈可见性（MVP：仅好友可见 + 自己可见自己）。
 */
@Component
public class MomentVisibilityService {

    private final FriendRelationService friendRelationService;

    public MomentVisibilityService(FriendRelationService friendRelationService) {
        this.friendRelationService = friendRelationService;
    }

    public boolean canViewAuthor(long viewerId, long authorId) {
        if (viewerId <= 0 || authorId <= 0) {
            return false;
        }
        if (viewerId == authorId) {
            return true;
        }
        return friendRelationService.friendIdSet(viewerId).contains(authorId);
    }
}

