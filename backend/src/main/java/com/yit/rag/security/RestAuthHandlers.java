package com.yit.rag.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yit.rag.common.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 认证/授权失败的统一 JSON 响应。
 *
 * <p>这个类解决什么问题：Spring Security 默认的失败响应是空的 403/401 页面，
 * 前端拿到一个没有 body 的响应完全不知道发生了什么。这里把它改成项目的
 * 统一格式 {@code {code, message, data}}，前端拦截器只维护一套解析逻辑即可。
 *
 * <p>两个处理器语义不同，不能混：
 * <ul>
 *   <li>{@link AuthenticationEntryPoint}（401）：<b>没登录</b>或 token 无效 → 前端应跳登录页</li>
 *   <li>{@link AccessDeniedHandler}（403）：<b>已登录但没权限</b> → 前端应提示「无权限」而不是跳登录页</li>
 * </ul>
 * 这两者搞混是很常见的 bug：权限不足却把用户踢回登录页，体验很差。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestAuthHandlers {

    private final ObjectMapper objectMapper;

    /** 401：未认证 */
    public AuthenticationEntryPoint entryPoint() {
        return (HttpServletRequest request, HttpServletResponse response,
                AuthenticationException authException) -> write(response, HttpServletResponse.SC_UNAUTHORIZED,
                R.CODE_UNAUTHORIZED, "未登录或登录已过期，请重新登录");
    }

    /** 403：已认证但权限不足 */
    public AccessDeniedHandler accessDeniedHandler() {
        return (HttpServletRequest request, HttpServletResponse response,
                AccessDeniedException accessDeniedException) -> {
            log.warn("权限不足: {} {} -> {}", request.getMethod(), request.getRequestURI(),
                    accessDeniedException.getMessage());
            write(response, HttpServletResponse.SC_FORBIDDEN,
                    R.CODE_FORBIDDEN, "没有该操作的权限");
        };
    }

    private void write(HttpServletResponse response, int httpStatus, int code, String message)
            throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(code, message)));
        response.getWriter().flush();
    }
}
