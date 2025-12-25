package com.miniim.auth.web;

/**
 * 请求级别的“当前用户”上下文。
 *
 * <p>本项目当前没有完整引入 Spring Security，而是先用拦截器解析 accessToken，
 * 把 userId 放到 ThreadLocal 里，方便 service 层随取随用。</p>
 *
 * <p>注意：ThreadLocal 一定要在请求结束时清理，否则线程复用时会串号。
 * 我们在 AccessTokenInterceptor#afterCompletion 里 clear。</p>
 */
public final class AuthContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    public static void clear() {
        USER_ID.remove();
    }
}
