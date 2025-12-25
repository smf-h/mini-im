package com.miniim.auth.service;

import java.time.Duration;

public interface RefreshTokenStore {

    record RefreshSession(long userId) {
    }

    void put(String tokenHash, RefreshSession session, Duration ttl);

    RefreshSession get(String tokenHash);

    void touch(String tokenHash, Duration ttl);

    void delete(String tokenHash);
}
