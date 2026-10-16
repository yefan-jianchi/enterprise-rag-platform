package com.yit.rag.api;

import com.yit.rag.api.dto.auth.LoginRequest;
import com.yit.rag.api.dto.auth.LoginResponse;
import com.yit.rag.common.R;
import com.yit.rag.service.system.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 *
 * <p>这个类解决什么问题：提供「登录换 token」和「刷新当前用户信息」两个入口。
 *
 * <p>注意 {@code /api/auth/login} 是唯一匿名可访问的业务接口（见 SecurityConfig 白名单），
 * 其余接口都必须带 {@code Authorization: Bearer <token>}。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "认证", description = "登录、当前用户")
public class AuthController {

    private final AuthService authService;

    /**
     * 登录：校验 租户编码 + 用户名 + 密码，返回 JWT 与用户信息。
     */
    @PostMapping("/login")
    @Operation(summary = "登录", description = "校验租户编码/用户名/密码，返回 JWT 与用户信息（含角色与权限码）")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                  HttpServletRequest httpRequest) {
        return R.ok(authService.login(request, httpRequest));
    }

    /**
     * 取当前登录用户。
     *
     * <p>前端刷新页面后调这个接口恢复登录态（token 存在 localStorage 里）。
     */
    @GetMapping("/me")
    @Operation(summary = "当前用户", description = "根据 token 返回当前用户信息、角色与权限码")
    public R<LoginResponse.UserInfo> me() {
        return R.ok(authService.currentUser());
    }
}
