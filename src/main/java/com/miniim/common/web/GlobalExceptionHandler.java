package com.miniim.common.web;

import com.miniim.common.api.ApiCodes;
import com.miniim.common.api.Result;
import com.miniim.common.ratelimit.RateLimitExceededException;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：把常见异常“翻译”为统一的 Result JSON。
 *
 * <p>注意：HTTP 状态码仍然会设置（比如 400/401/500），但响应体结构始终一致。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Spring Validation（@Valid）触发的参数错误。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        // 这里取第一个错误信息即可；如果你想把所有字段错误都返回，可以扩展 data 字段。
        String msg = e.getBindingResult().getAllErrors().isEmpty()
            ? "invalid_request"
            : e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ApiCodes.BAD_REQUEST, msg));
    }

    /**
     * 业务校验失败（我们在 service 层用 IllegalArgumentException 表达）。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleBadRequest(IllegalArgumentException e) {
        String msg = (e.getMessage() == null || e.getMessage().isBlank()) ? "bad_request" : e.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ApiCodes.BAD_REQUEST, msg));
    }

    /**
     * 唯一键冲突等（例如用户名已存在）。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("duplicate key", e);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ApiCodes.BAD_REQUEST, "duplicate_key"));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Result<Void>> handleRateLimit(RateLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(Math.max(1, e.getRetryAfterSeconds())))
                .body(Result.fail(ApiCodes.TOO_MANY_REQUESTS, e.getMessage() == null ? "too_many_requests" : e.getMessage()));
    }

    /**
     * Redis 不可用：登录/刷新等依赖 Redis 的能力会失败。
     */
    @ExceptionHandler(RedisConnectionFailureException.class)
    public ResponseEntity<Result<Void>> handleRedisDown(RedisConnectionFailureException e) {
        log.error("redis unavailable", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ApiCodes.INTERNAL_ERROR, "redis_unavailable"));
    }

    /**
     * JWT 解析失败：通常算未授权。
     */
    @ExceptionHandler(JwtException.class)
    public ResponseEntity<Result<Void>> handleJwt(JwtException e) {
        String msg = (e.getMessage() == null || e.getMessage().isBlank()) ? "invalid_token" : e.getMessage();
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.fail(ApiCodes.UNAUTHORIZED, msg));
    }

    /**
     * 未匹配到任何 Controller 且静态资源也不存在：应返回 404，而不是被兜底异常处理为 500。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResourceFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.fail(ApiCodes.NOT_FOUND, "not_found"));
    }

    /**
     * 兜底：避免默认 HTML 错误页。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleAny(Exception e) {
        log.error("unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ApiCodes.INTERNAL_ERROR, "internal_error"));
    }
}
