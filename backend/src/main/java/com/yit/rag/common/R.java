package com.yit.rag.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应包装 {@code R<T>}。
 *
 * <p>这个类解决什么问题：前端只需要判断一个 {@code code} 字段就能知道成功与否，
 * 不用去猜 HTTP 状态码和业务错误的组合。约定：
 * <ul>
 *   <li>{@code code = 0} 表示成功，{@code data} 是业务数据</li>
 *   <li>{@code code != 0} 表示失败，{@code message} 是可以直接弹给用户看的中文提示</li>
 * </ul>
 * HTTP 状态码仍然保留语义（401 未认证 / 403 无权限 / 500 服务异常），
 * 前端拦截器两层都能用。
 *
 * @param <T> 业务数据类型
 */
@Data
public class R<T> implements Serializable {

    /** 成功码 */
    public static final int CODE_OK = 0;
    /** 通用失败码 */
    public static final int CODE_FAIL = 500;
    /** 未认证 */
    public static final int CODE_UNAUTHORIZED = 401;
    /** 无权限 */
    public static final int CODE_FORBIDDEN = 403;
    /** 参数/业务校验失败 */
    public static final int CODE_BAD_REQUEST = 400;

    private int code;
    private String message;
    private T data;

    public R() {
    }

    public R(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // ------------------------- 成功 -------------------------

    public static <T> R<T> ok() {
        return new R<>(CODE_OK, "ok", null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(CODE_OK, "ok", data);
    }

    public static <T> R<T> ok(T data, String message) {
        return new R<>(CODE_OK, message, data);
    }

    // ------------------------- 失败 -------------------------

    public static <T> R<T> fail(String message) {
        return new R<>(CODE_FAIL, message, null);
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null);
    }

    public boolean isSuccess() {
        return this.code == CODE_OK;
    }
}
