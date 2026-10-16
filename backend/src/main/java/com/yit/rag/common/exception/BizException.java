package com.yit.rag.common.exception;

import lombok.Getter;

/**
 * 业务异常。
 *
 * <p>这个类解决什么问题：把「可预期的业务失败」（知识库不存在、文档还在处理中、
 * 租户配额用完……）与「不可预期的系统故障」区分开。
 * 业务异常会被 {@code GlobalExceptionHandler} 转成 HTTP 200 + {@code code != 0} 的
 * 友好提示；系统故障才会打 ERROR 日志并返回 500。
 *
 * <p>继承 {@link RuntimeException} 而不是受检异常，避免在 Service 层到处 {@code throws}。
 */
@Getter
public class BizException extends RuntimeException {

    /** 业务错误码，默认 500 */
    private final int code;

    public BizException(String message) {
        this(500, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message, Throwable cause) {
        super(message, cause);
        this.code = 500;
    }

    // ------------------------- 便捷工厂 -------------------------

    public static BizException of(String message) {
        return new BizException(message);
    }

    public static BizException badRequest(String message) {
        return new BizException(400, message);
    }

    public static BizException unauthorized(String message) {
        return new BizException(401, message);
    }

    public static BizException forbidden(String message) {
        return new BizException(403, message);
    }

    public static BizException notFound(String message) {
        return new BizException(404, message);
    }
}
