package com.miniim.gateway.session;

import com.miniim.gateway.config.GatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WS 多实例路由存储（Redis）：
 * <ul>
 *   <li>key: userId -> value: serverId|connId</li>
 *   <li>用于定位“某个 user 当前在哪个网关实例上有活跃连接”</li>
 * </ul>
 */
@Slf4j
@Component
public class WsRouteStore {

    private static final String ROUTE_KEY_PREFIX = "im:gw:route:";

    /**
     * Redis 故障时做 fail-fast：避免每次路由查询都阻塞在 Redis 连接/超时上，从而把尾延迟放大成“雪崩”。
     *
     * <p>注意：这是“可用性优先”的折中——短时间内可能导致跨实例路由能力下降（本来 Redis 已不可用）。</p>
     */
    private static final long REDIS_FAIL_FAST_MS = 10_000;
    private static final AtomicLong REDIS_UNAVAILABLE_UNTIL_MS = new AtomicLong(0);

    private final StringRedisTemplate redis;
    private final String serverId;

    private final DefaultRedisScript<String> setAndGetOldScript;
    private final DefaultRedisScript<Long> expireIfMatchScript;
    private final DefaultRedisScript<Long> delIfMatchScript;

    public WsRouteStore(StringRedisTemplate redis, GatewayProperties wsProps) {
        this.redis = redis;
        this.serverId = wsProps.effectiveInstanceId();

        this.setAndGetOldScript = new DefaultRedisScript<>();
        this.setAndGetOldScript.setResultType(String.class);
        this.setAndGetOldScript.setScriptText("""
                local old = redis.call('GET', KEYS[1])
                redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
                return old
                """);

        this.expireIfMatchScript = new DefaultRedisScript<>();
        this.expireIfMatchScript.setResultType(Long.class);
        this.expireIfMatchScript.setScriptText("""
                local cur = redis.call('GET', KEYS[1])
                if cur == ARGV[1] then
                  return redis.call('EXPIRE', KEYS[1], ARGV[2])
                end
                return 0
                """);

        this.delIfMatchScript = new DefaultRedisScript<>();
        this.delIfMatchScript.setResultType(Long.class);
        this.delIfMatchScript.setScriptText("""
                local cur = redis.call('GET', KEYS[1])
                if cur == ARGV[1] then
                  return redis.call('DEL', KEYS[1])
                end
                return 0
                """);
    }

    public String serverId() {
        return serverId;
    }

    public RouteInfo get(long userId) {
        if (shouldFailFast()) {
            return null;
        }
        String key = routeKey(userId);
        try {
            String raw = redis.opsForValue().get(key);
            return parse(raw);
        } catch (Exception e) {
            log.debug("ws route get failed: userId={}, err={}", userId, e.toString());
            markRedisDown();
            return null;
        }
    }

    public RouteInfo setAndGetOld(long userId, String connId, Duration ttl) {
        if (shouldFailFast()) {
            return null;
        }
        String key = routeKey(userId);
        String value = format(serverId, connId);
        long ttlSeconds = ttl == null ? 0 : ttl.toSeconds();
        if (ttlSeconds <= 0) {
            ttlSeconds = 120;
        }
        try {
            String old = redis.execute(setAndGetOldScript, List.of(key), value, String.valueOf(ttlSeconds));
            return parse(old);
        } catch (Exception e) {
            log.warn("ws route set failed, redis unavailable? userId={}, serverId={}, err={}", userId, serverId, e.toString());
            markRedisDown();
            return null;
        }
    }

    public boolean expireIfMatch(long userId, String connId, Duration ttl) {
        if (shouldFailFast()) {
            return false;
        }
        String key = routeKey(userId);
        String expected = format(serverId, connId);
        long ttlSeconds = ttl == null ? 0 : ttl.toSeconds();
        if (ttlSeconds <= 0) {
            ttlSeconds = 120;
        }
        try {
            Long ok = redis.execute(expireIfMatchScript, List.of(key), expected, String.valueOf(ttlSeconds));
            return ok != null && ok > 0;
        } catch (Exception e) {
            log.debug("ws route expire failed: userId={}, serverId={}, err={}", userId, serverId, e.toString());
            markRedisDown();
            return false;
        }
    }

    public boolean deleteIfMatch(long userId, String connId) {
        if (shouldFailFast()) {
            return false;
        }
        String key = routeKey(userId);
        String expected = format(serverId, connId);
        try {
            Long ok = redis.execute(delIfMatchScript, List.of(key), expected);
            return ok != null && ok > 0;
        } catch (Exception e) {
            log.debug("ws route delete failed: userId={}, serverId={}, err={}", userId, serverId, e.toString());
            markRedisDown();
            return false;
        }
    }

    private String routeKey(long userId) {
        return ROUTE_KEY_PREFIX + userId;
    }

    public static String routeKeyOf(long userId) {
        return ROUTE_KEY_PREFIX + userId;
    }

    /**
     * 批量读取路由（MGET）。
     *
     * @return 返回 map 表示在线路由；若 Redis 失败则返回 {@code null} 以便调用方做 fail-open 降级。
     */
    public Map<Long, RouteInfo> batchGet(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        if (shouldFailFast()) {
            return null;
        }
        List<Long> ids = new ArrayList<>(userIds.size());
        List<String> keys = new ArrayList<>(userIds.size());
        for (Long uid : userIds) {
            if (uid == null || uid <= 0) {
                continue;
            }
            ids.add(uid);
            keys.add(routeKeyOf(uid));
        }
        if (ids.isEmpty()) {
            return Map.of();
        }

        try {
            List<String> values = redis.opsForValue().multiGet(keys);
            if (values == null || values.isEmpty()) {
                return Map.of();
            }
            Map<Long, RouteInfo> out = new java.util.HashMap<>();
            for (int i = 0; i < ids.size() && i < values.size(); i++) {
                String raw = values.get(i);
                RouteInfo info = parse(raw);
                if (info == null || info.serverId() == null || info.serverId().isBlank()) {
                    continue;
                }
                out.put(ids.get(i), info);
            }
            return out;
        } catch (Exception e) {
            log.debug("ws route batch get failed: size={}, err={}", ids.size(), e.toString());
            markRedisDown();
            return null;
        }
    }

    public static String format(String serverId, String connId) {
        String s = serverId == null ? "" : serverId;
        String c = connId == null ? "" : connId;
        return s + "|" + c;
    }

    public static RouteInfo parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int idx = raw.indexOf('|');
        if (idx < 0) {
            // 兼容旧格式：值只存 serverId
            return new RouteInfo(raw.trim(), null, raw);
        }
        String s = raw.substring(0, idx).trim();
        String c = raw.substring(idx + 1).trim();
        return new RouteInfo(s.isEmpty() ? null : s, c.isEmpty() ? null : c, raw);
    }

    public record RouteInfo(String serverId, String connId, String raw) {
    }

    private static boolean shouldFailFast() {
        return System.currentTimeMillis() < REDIS_UNAVAILABLE_UNTIL_MS.get();
    }

    private static void markRedisDown() {
        long until = System.currentTimeMillis() + REDIS_FAIL_FAST_MS;
        while (true) {
            long prev = REDIS_UNAVAILABLE_UNTIL_MS.get();
            if (prev >= until) {
                return;
            }
            if (REDIS_UNAVAILABLE_UNTIL_MS.compareAndSet(prev, until)) {
                return;
            }
        }
    }
}
