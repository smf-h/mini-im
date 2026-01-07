package com.miniim.common.ratelimit;

import com.miniim.auth.dto.LoginRequest;
import com.miniim.auth.web.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.List;

@Slf4j
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RateLimitProperties props;
    private final StringRedisTemplate redis;

    private final DefaultRedisScript<Long> script = buildScript();

    @Around("@annotation(com.miniim.common.ratelimit.RateLimit)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        if (!props.isEnabled()) {
            return pjp.proceed();
        }

        RateLimit rateLimit = resolveRateLimitAnnotation(pjp);
        if (rateLimit == null) {
            return pjp.proceed();
        }

        HttpServletRequest req = currentRequest();
        if (req == null) {
            return pjp.proceed();
        }

        String key = buildKey(req, pjp.getArgs(), rateLimit);
        if (key == null || key.isBlank()) {
            return pjp.proceed();
        }

        try {
            Long retryAfter = redis.execute(
                    script,
                    List.of(key),
                    String.valueOf(Math.max(1, rateLimit.windowSeconds())),
                    String.valueOf(Math.max(1, rateLimit.max()))
            );
            if (retryAfter != null && retryAfter > 0) {
                throw new RateLimitExceededException("too_many_requests", retryAfter);
            }
            return pjp.proceed();
        } catch (RateLimitExceededException e) {
            throw e;
        } catch (Exception e) {
            if (props.isFailOpen()) {
                log.debug("ratelimit redis failed, fail-open: name={}, err={}", rateLimit.name(), e.toString());
                return pjp.proceed();
            }
            throw new RateLimitExceededException("too_many_requests", Math.max(1, rateLimit.windowSeconds()));
        }
    }

    private String buildKey(HttpServletRequest req, Object[] args, RateLimit rateLimit) {
        String prefix = props.getKeyPrefix() == null ? "" : props.getKeyPrefix();
        String name = rateLimit.name();
        RateLimitKey keyType = rateLimit.key();

        String value;
        if (keyType == RateLimitKey.IP) {
            value = resolveIp(req);
        } else if (keyType == RateLimitKey.USER) {
            Long uid = AuthContext.getUserId();
            value = uid == null ? null : String.valueOf(uid);
        } else if (keyType == RateLimitKey.IP_USER) {
            String ip = resolveIp(req);
            String username = extractUsername(args);
            if (username == null || username.isBlank()) {
                username = req.getParameter("username");
            }
            if (ip == null || username == null) {
                value = null;
            } else {
                value = ip + ":" + username.trim().toLowerCase();
            }
        } else {
            value = null;
        }

        if (value == null || value.isBlank()) {
            return null;
        }
        String dim = keyType.name();
        return prefix + name + ":" + dim + ":" + value;
    }

    String extractUsername(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof LoginRequest r) {
                return r.username();
            }
        }
        return null;
    }

    String resolveIp(HttpServletRequest req) {
        if (req == null) {
            return null;
        }
        if (props.isTrustForwardedHeaders()) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String first = xff.split(",")[0].trim();
                if (!first.isBlank()) {
                    return first;
                }
            }
            String xri = req.getHeader("X-Real-IP");
            if (xri != null && !xri.isBlank()) {
                return xri.trim();
            }
        }
        return req.getRemoteAddr();
    }

    private HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs == null ? null : attrs.getRequest();
        } catch (Exception ignore) {
            return null;
        }
    }

    private static DefaultRedisScript<Long> buildScript() {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setResultType(Long.class);
        s.setScriptText("""
                local c = redis.call('INCR', KEYS[1])
                if c == 1 then
                  redis.call('EXPIRE', KEYS[1], ARGV[1])
                end
                if c > tonumber(ARGV[2]) then
                  local ttl = redis.call('TTL', KEYS[1])
                  if ttl < 0 then ttl = tonumber(ARGV[1]) end
                  return ttl
                end
                return 0
                """);
        return s;
    }

    private static RateLimit resolveRateLimitAnnotation(ProceedingJoinPoint pjp) {
        if (pjp == null || !(pjp.getSignature() instanceof MethodSignature sig)) {
            return null;
        }

        Method method = sig.getMethod();
        RateLimit ann = method.getAnnotation(RateLimit.class);
        if (ann != null) {
            return ann;
        }

        Object target = pjp.getTarget();
        if (target == null) {
            return null;
        }
        Method impl = ReflectionUtils.findMethod(target.getClass(), method.getName(), method.getParameterTypes());
        return impl == null ? null : impl.getAnnotation(RateLimit.class);
    }
}
