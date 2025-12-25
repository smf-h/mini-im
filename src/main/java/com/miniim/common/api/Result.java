package com.miniim.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一 HTTP 返回对象。
 *
 * <p>目标：让所有 HTTP 接口在成功/失败时都有同一种 JSON 结构，方便前端/网关/脚本统一处理。</p>
 *
 * <p>字段约定：</p>
 * <ul>
 *   <li>ok：业务是否成功（不是 HTTP 状态码）。</li>
 *   <li>code：业务错误码。成功固定为 0；失败为非 0（建议按模块划分区间）。</li>
 *   <li>message：给调用方看的简短原因（可直接展示或用于日志）。</li>
 *   <li>data：成功时的数据；失败通常为 null。</li>
 *   <li>ts：服务端响应时间戳（毫秒）。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Result<T>(
        boolean ok,
        int code,
        String message,
        T data,
        long ts
) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(true, 0, "ok", data, System.currentTimeMillis());
    }

    /**
     * 成功但无业务数据时使用。
     *
     * <p>注意：不能命名为 ok()，因为 record 会自动生成 ok() 访问器（boolean）。</p>
     */
    public static <T> Result<T> okVoid() {
        return new Result<>(true, 0, "ok", null, System.currentTimeMillis());
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(false, code, message, null, System.currentTimeMillis());
    }
}
