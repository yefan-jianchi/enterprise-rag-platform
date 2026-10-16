package com.yit.rag.api.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求体。
 *
 * <p>{@code tenantCode} 是必填的：多租户系统里「用户名」只在租户内唯一，
 * 没有租户编码就无法确定是哪个租户的 admin。
 */
public record LoginRequest(
        @NotBlank(message = "租户编码不能为空") String tenantCode,
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password
) {
}
