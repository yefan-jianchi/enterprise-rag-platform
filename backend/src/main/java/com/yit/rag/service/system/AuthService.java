package com.yit.rag.service.system;

import com.yit.rag.api.dto.auth.LoginRequest;
import com.yit.rag.api.dto.auth.LoginResponse;
import com.yit.rag.common.exception.BizException;
import com.yit.rag.common.tenant.TenantContext;
import com.yit.rag.common.util.JwtUtil;
import com.yit.rag.domain.system.SysUser;
import com.yit.rag.domain.system.Tenant;
import com.yit.rag.repository.system.SysUserRepository;
import com.yit.rag.security.SecurityUser;
import com.yit.rag.security.UserDetailsServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 认证服务 —— 登录与「取当前用户」。
 *
 * <p>这个类解决什么问题：把「校验账号密码 → 签发 JWT → 写审计」这条链路收敛到一处。
 *
 * <p>登录流程（每一步都有明确目的）：
 * <ol>
 *   <li>把 {@code tenantCode + username} 拼成 {@code demo:admin} 交给
 *       {@link AuthenticationManager} —— 这一步复用 Spring Security 的
 *       密码校验、账号停用检查逻辑，不在业务代码里手写
 *       {@code passwordEncoder.matches()}，避免漏掉「账号被禁用」这类分支</li>
 *   <li>校验通过后把 roles / permissions 一起塞进 JWT —— 后续请求就不用查库了</li>
 *   <li>更新 {@code last_login_at}，供「僵尸账号清理」等运营动作使用</li>
 *   <li>写登录审计（成功与失败都写）—— 失败记录是排查暴力破解的依据</li>
 * </ol>
 *
 * <p>安全细节：登录失败时对外统一提示「用户名或密码错误」，
 * 不区分「用户不存在」和「密码错误」，避免攻击者用提示差异枚举有效用户名。
 * 但审计日志里会如实记录，因为那是给管理员看的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserDetailsServiceImpl userDetailsService;
    private final SysUserRepository userRepository;
    private final AuditService auditService;

    /**
     * 登录。
     */
    @Transactional
    public LoginResponse login(LoginRequest req, HttpServletRequest httpRequest) {
        String loginKey = req.tenantCode() + UserDetailsServiceImpl.TENANT_SEPARATOR + req.username();
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginKey, req.password()));
        } catch (DisabledException e) {
            auditService.logLogin(null, null, req.username(), "账号已停用", false, httpRequest);
            throw BizException.forbidden("账号已被停用，请联系管理员");
        } catch (BadCredentialsException e) {
            auditService.logLogin(null, null, req.username(), "用户名或密码错误", false, httpRequest);
            throw BizException.unauthorized("用户名或密码错误");
        } catch (AuthenticationException e) {
            auditService.logLogin(null, null, req.username(), "认证失败: " + e.getMessage(), false, httpRequest);
            throw BizException.unauthorized("登录失败：" + e.getMessage());
        }

        SecurityUser principal = (SecurityUser) authentication.getPrincipal();

        // 签发 JWT：把多租户 + RBAC 需要的信息全部放进去
        String token = jwtUtil.generate(
                principal.getUserId(),
                principal.getTenantId(),
                principal.getTenantCode(),
                principal.getUsername(),
                principal.getRoles(),
                principal.permissionCodes());

        // 更新最后登录时间
        SysUser user = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> BizException.notFound("用户不存在"));
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        auditService.logLogin(principal.getTenantId(), principal.getUserId(), principal.getUsername(),
                "登录成功", true, httpRequest);

        log.info("用户登录成功: tenant={} username={} roles={}",
                principal.getTenantCode(), principal.getUsername(), principal.getRoles());

        return new LoginResponse(token, toUserInfo(principal, user.getNickname()));
    }

    /**
     * 取当前登录用户信息。
     *
     * <p>不直接用 JWT 里的内容，而是<b>重新查一次库</b>。
     * 原因：管理员刚改过某人的权限时，旧 token 里的权限是过期的，
     * 前端调 /me 刷新一下就能立刻生效，比等 token 过期体验好得多。
     */
    @Transactional(readOnly = true)
    public LoginResponse.UserInfo currentUser() {
        Long userId = TenantContext.getUserIdOrNull();
        if (userId == null) {
            throw BizException.unauthorized("未登录");
        }
        SysUser user = userRepository.findById(userId)
                .orElseThrow(() -> BizException.unauthorized("用户不存在或已被删除"));
        Tenant tenant = userDetailsService.findTenant(user.getTenantId());
        List<String> roles = new ArrayList<>(userDetailsService.loadRoles(user.getId()));
        List<String> permissions = new ArrayList<>(userDetailsService.loadPermissions(user.getId()));

        return new LoginResponse.UserInfo(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                tenant.getId(),
                tenant.getTenantCode(),
                roles,
                permissions);
    }

    private LoginResponse.UserInfo toUserInfo(SecurityUser principal, String nickname) {
        return new LoginResponse.UserInfo(
                principal.getUserId(),
                principal.getUsername(),
                nickname != null ? nickname : principal.getNickname(),
                principal.getTenantId(),
                principal.getTenantCode(),
                new ArrayList<>(principal.getRoles()),
                new ArrayList<>(principal.permissionCodes()));
    }
}
