package com.miniim.domain.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MomentVisibilityServiceTest {

    @Test
    void canViewAuthor_ShouldAllowSelfAndFriends() {
        FriendRelationService friendRelationService = mock(FriendRelationService.class);
        when(friendRelationService.friendIdSet(1L)).thenReturn(Set.of(2L, 3L));

        MomentVisibilityService svc = new MomentVisibilityService(friendRelationService);

        assertTrue(svc.canViewAuthor(1L, 1L));
        assertTrue(svc.canViewAuthor(1L, 2L));
        assertFalse(svc.canViewAuthor(1L, 4L));
    }

    @Test
    void canViewAuthor_ShouldRejectBadIds() {
        FriendRelationService friendRelationService = mock(FriendRelationService.class);
        MomentVisibilityService svc = new MomentVisibilityService(friendRelationService);

        assertFalse(svc.canViewAuthor(0L, 1L));
        assertFalse(svc.canViewAuthor(1L, 0L));
        assertFalse(svc.canViewAuthor(-1L, 2L));
        assertFalse(svc.canViewAuthor(1L, -2L));

        verifyNoInteractions(friendRelationService);
    }
}

