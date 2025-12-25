package com.miniim.common.api;

/**
 * 统一错误码定义。
 *
 * <p>目前只做最小集合，后续你可以按模块扩展（例如：Auth 10xxx，Message 20xxx，Group 30xxx）。</p>
 */
public final class ApiCodes {

    private ApiCodes() {
    }

    /** 参数不合法 / 业务校验失败 */
    public static final int BAD_REQUEST = 40000;

    /** 未登录 / token 无效 */
    public static final int UNAUTHORIZED = 40100;

    /** 服务端未预期异常 */
    public static final int INTERNAL_ERROR = 50000;
}
