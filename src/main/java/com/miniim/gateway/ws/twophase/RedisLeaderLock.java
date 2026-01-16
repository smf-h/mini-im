package com.miniim.gateway.ws.twophase;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

public class RedisLeaderLock {

    private final StringRedisTemplate redis;
    private final String lockKey;
    private final String ownerId;

    private final DefaultRedisScript<Long> expireIfMatchScript;

    public RedisLeaderLock(StringRedisTemplate redis, String lockKey, String ownerId) {
        this.redis = redis;
        this.lockKey = lockKey;
        this.ownerId = ownerId;

        this.expireIfMatchScript = new DefaultRedisScript<>();
        this.expireIfMatchScript.setResultType(Long.class);
        this.expireIfMatchScript.setScriptText("""
                local cur = redis.call('GET', KEYS[1])
                if cur == ARGV[1] then
                  return redis.call('PEXPIRE', KEYS[1], ARGV[2])
                end
                return 0
                """);
    }

    public boolean tryAcquire(Duration ttl) {
        long ms = ttl == null ? 0 : ttl.toMillis();
        if (ms <= 0) {
            ms = 2000;
        }
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, ownerId, Duration.ofMillis(ms)));
        } catch (Exception e) {
            return false;
        }
    }

    public boolean renew(Duration ttl) {
        long ms = ttl == null ? 0 : ttl.toMillis();
        if (ms <= 0) {
            ms = 2000;
        }
        try {
            Long ok = redis.execute(expireIfMatchScript, List.of(lockKey), ownerId, String.valueOf(ms));
            return ok != null && ok > 0;
        } catch (Exception e) {
            return false;
        }
    }
}

