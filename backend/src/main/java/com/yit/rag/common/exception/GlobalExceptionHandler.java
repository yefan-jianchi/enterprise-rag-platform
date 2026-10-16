package com.yit.rag.common.exception;

import com.yit.rag.common.R;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>这个类解决什么问题：让 Controller 层彻底不写 try-catch。
 * 所有异常在这里统一翻译成 {@link R}，并且做三件事：
 * <ol>
 *   <li>可预期的业务异常 → WARN 日志 + 中文提示（不打堆栈，避免日志噪音）</li>
 *   <li>参数校验异常 → 把字段名和原因拼成人话返回给前端</li>
 *   <li>真正的系统异常 → ERROR 日志 + 完整堆栈，对外只给一句兜底提示（不泄露内部细节）</li>
 * </ol>
 *
 * <p>注意：SSE 接口（/api/chat/stream 等）的异常不走这里 —— 流一旦开始就无法改状态码，
 * 必须由 Controller 自己往流里写 {@code event: error}。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：这是预期内的，用 WARN 而不是 ERROR */
    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.OK)
    public R<Void> handleBiz(BizException e, HttpServletRequest request) {
        log.warn("业务异常 [{} {}] code={} msg={}", request.getMethod(), request.getRequestURI(),
                e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    /** @Valid 校验失败：把「字段: 原因」拼起来，前端可以直接展示 */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    @ResponseStatus(HttpStatus.OK)
    public R<Void> handleValidation(BindException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> ((FieldError) fe).getField() + ": " + fe.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", detail);
        return R.fail(R.CODE_BAD_REQUEST, "参数校验失败 -> " + detail);
    }

    /** 缺少必填请求参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.OK)
    public R<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return R.fail(R.CODE_BAD_REQUEST, "缺少必填参数: " + e.getParameterName());
    }

    /** 请求体 JSON 格式错误 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.OK)
    public R<Void> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return R.fail(R.CODE_BAD_REQUEST, "请求体格式错误，请检查 JSON 结构");
    }

    /** 上传文件超限 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.OK)
    public R<Void> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return R.fail(R.CODE_BAD_REQUEST, "上传文件超过大小限制（单文件 50MB）");
    }

    /** 方法不支持 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.OK)
    public R<Void> handleMethod(HttpRequestMethodNotSupportedException e) {
        return R.fail(R.CODE_BAD_REQUEST, "请求方法不支持: " + e.getMethod());
    }

    /** 认证失败（token 缺失/过期） */
    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public R<Void> handleAuth(AuthenticationException e) {
        log.warn("认证失败: {}", e.getMessage());
        return R.fail(R.CODE_UNAUTHORIZED, "登录状态已失效，请重新登录");
    }

    /** 权限不足（@PreAuthorize 拦截） */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public R<Void> handleAccessDenied(AccessDeniedException e) {
        log.warn("权限不足: {}", e.getMessage());
        return R.fail(R.CODE_FORBIDDEN, "没有该操作的权限");
    }

    /** 兜底：所有没被上面接住的异常 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleOthers(Exception e, HttpServletRequest request) {
        log.error("系统异常 [{} {}]", request.getMethod(), request.getRequestURI(), e);
        return R.fail(R.CODE_FAIL, "服务内部错误，请稍后重试或联系管理员");
    }
}
