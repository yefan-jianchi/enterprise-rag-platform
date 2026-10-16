package com.yit.rag.security;

import com.yit.rag.common.tenant.TenantContext;
import com.yit.rag.common.util.JwtUtil;
import com.yit.rag.config.AppProperties;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * JWT 认证过滤器 —— 多租户上下文的入口。
 *
 * <p>这个类解决什么问题：每个请求都要回答两个问题：
 * <ol>
 *   <li><b>你是谁</b>（认证）→ 校验 JWT 签名与有效期，把用户/角色/权限放进
 *       {@code SecurityContext}，这样 {@code @PreAuthorize} 才能工作</li>
 *   <li><b>你属于哪个租户</b>（多租户隔离）→ 把租户信息写进 {@link TenantContext}，
 *       Service/Repository 后续所有查询都靠它拼 tenant_id 条件</li>
 * </ol>
 * 两件事都在这里一次做完，业务代码就不用重复解析 token。
 *
 * <p>为什么继承 {@code OncePerRequestFilter}：保证一个请求只过滤一次
 * （forward/include 不会重复执行），否则 TenantContext 会被覆盖多次。
 *
 * <p>安全设计（重点）：
 * <ul>
 *   <li>解析失败<b>不抛异常</b>，只是不设置认证信息，让后续的授权规则去拒绝。
 *       这样 {@code /api/auth/login} 这类匿名接口才能正常访问。</li>
 *   <li>{@code SecurityContextHolder.clearContext()} 与 {@link TenantContext#clear()}
 *       都放在 {@code finally} 里。Tomcat 线程是复用的，
 *       不清理就会把上一个请求的租户带给下一个请求 —— 这是多租户系统最典型的事故。</li>
 *   <li>匿名请求也清理：防止「先登录请求用了线程 A，然后匿名请求复用线程 A
 *       读到残留的租户上下文」。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final AppProperties props;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        try {
            String token = extractToken(request);
            if (token != null) {
                Claims claims = jwtUtil.parse(token);
                if (claims != null) {
                    authenticate(request, claims);
                } else {
                    log.debug("无效或已过期的 JWT: {} {}", request.getMethod(), request.getRequestURI());
                }
            }
            chain.doFilter(request, response);
        } finally {
            // 必须清理：线程池复用会串租户 / 串用户
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 从请求头里取出 token。
     *
     * <p>按 {@code app.jwt.header} / {@code app.jwt.prefix} 配置来，
     * 默认 {@code Authorization: Bearer <token>}。同时兼容不带前缀直接放 token 的情况
     * （有些压测工具/调试脚本图省事），缺失时自动补 Bearer 语义。
     */
    private String extractToken(HttpServletRequest request) {
        String header = props.getJwt().getHeader();
        String value = request.getHeader(header);
        if (value == null || value.isBlank()) {
            // 兼容前端 EventSource 无法自定义 header 的场景：允许 query 传 token
            value = request.getParameter("token");
            if (value == null || value.isBlank()) {
                return null;
            }
            return value.trim();
        }
        String prefix = props.getJwt().getPrefix();
        if (prefix != null && !prefix.isBlank() && value.startsWith(prefix)) {
            return value.substring(prefix.length()).trim();
        }
        // 没带前缀也接受（容错），但记录一下便于前端排查
        log.debug("请求头 {} 未按 '{}' 格式携带 token，已按裸 token 处理", header, prefix);
        return value.trim();
    }

    /**
     * 校验通过后，同时填充 SecurityContext 与 TenantContext。
     */
    private void authenticate(HttpServletRequest request, Claims claims) {
        Long userId = toLong(claims.get(JwtUtil.CLAIM_USER_ID));
        Long tenantId = toLong(claims.get(JwtUtil.CLAIM_TENANT_ID));
        String tenantCode = (String) claims.get(JwtUtil.CLAIM_TENANT_CODE);
        String username = (String) claims.get(JwtUtil.CLAIM_USERNAME);
        Set<String> roles = JwtUtil.readStringSet(claims, JwtUtil.CLAIM_ROLES);
        Set<String> permissions = JwtUtil.readStringSet(claims, JwtUtil.CLAIM_PERMISSIONS);

        // 1) 写 SecurityContext：让 @PreAuthorize("hasAuthority('kb:doc:upload')") 生效
        Set<SimpleGrantedAuthority> authorities = new LinkedHashSet<>();
        permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(username, null, authorities);
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);

        // 2) 写 TenantContext：让所有业务查询自动带上 tenant_id
        TenantContext.set(new TenantContext.CurrentUser(
                userId, tenantId, tenantCode, username, null, roles, permissions));

        log.debug("认证通过: user={} tenantId={} roles={} 权限数={}",
                username, tenantId, roles, permissions.size());
    }

    /** JWT 里的数字经过 JSON 反序列化可能是 Integer，统一转 Long */
    private static Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 不需要认证的路径也走这个过滤器（因为要清理上下文），所以不做 shouldNotFilter 排除。
     * 真正放行哪些路径由 {@code SecurityConfig} 的 authorizeHttpRequests 决定。
     */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return false;
    }
}
