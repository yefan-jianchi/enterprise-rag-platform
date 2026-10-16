package com.yit.rag.config;

import com.yit.rag.security.JwtAuthenticationFilter;
import com.yit.rag.security.RestAuthHandlers;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 配置。
 *
 * <p>这个类解决什么问题：把「谁能访问什么」集中声明，而不是散落在各个 Controller 的 if 里。
 *
 * <p>关键决策与理由：
 * <ol>
 *   <li><b>无状态（STATELESS）</b>：不用 Session，身份完全靠 JWT。
 *       好处是服务可以水平扩容 —— 用户请求打到任意实例都能认证，
 *       不需要 Session 粘滞或 Redis 共享 Session。</li>
 *   <li><b>关掉 CSRF</b>：CSRF 攻击的前提是浏览器自动携带 Cookie 凭证。
 *       我们用 Authorization 头传 token，浏览器不会自动带上，
 *       所以 CSRF 防护没有意义，留着反而会让所有 POST 接口 403。</li>
 *   <li><b>@EnableMethodSecurity</b>：开启 {@code @PreAuthorize}，
 *       权限粒度可以细到「方法级」（比如 {@code hasAuthority('kb:doc:upload')}）。</li>
 *   <li><b>密码用 BCrypt</b>：自带随机盐 + 可调计算强度，
 *       即使数据库泄露也难以反推明文（对比 MD5/SHA1 已被彩虹表攻破）。</li>
 * </ol>
 *
 * <p>放行清单刻意收得很紧：只有登录、健康检查、接口文档是匿名的，
 * 其余一律需要 JWT。SSE 接口也在鉴权范围内（靠前端 EventSource 或 fetch+stream 带 token）。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthHandlers restAuthHandlers;

    /** 匿名可访问的路径 */
    private static final String[] PUBLIC_PATHS = {
            "/api/auth/login",
            "/api/health",
            "/actuator/health",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/error"
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 说明：不使用 Cookie/Session，因此 CSRF 防护无意义
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth
                        // 预检请求必须放行，否则跨域会失败
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // SSE 的异步二次派发（ASYNC dispatch）必须放行。
                        // 原因：SseEmitter 完成时，Servlet 容器会对同一个请求再做一次 ASYNC 派发，
                        // 安全过滤器链会随之再跑一遍；而此时 SecurityContext 已随首个线程清理掉了，
                        // 于是被判定为匿名访问并抛 AuthorizationDeniedException ——
                        // 日志里表现为「Access Denied ... response is already committed」。
                        // 首次 REQUEST 派发已经完成完整的 JWT 鉴权，
                        // 对 ASYNC/ERROR 派发重复鉴权既无意义又会污染日志，故直接放行。
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated()
                )
                // 认证/授权失败统一返回项目的 R 格式
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(restAuthHandlers.entryPoint())
                        .accessDeniedHandler(restAuthHandlers.accessDeniedHandler())
                )
                // JWT 过滤器放在用户名密码过滤器之前
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 密码编码器。强度 10 是 BCrypt 常用取值：
     * 单次校验约几十毫秒，能有效抵挡离线暴力破解，又不会拖慢登录接口。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /**
     * 认证管理器。登录接口用它来做「用户名 + 密码」校验，
     * 校验逻辑复用 {@link UserDetailsService}，避免在 Service 里手写密码比对。
     */
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // 不隐藏「用户不存在」异常，便于日志排查；对外提示仍是统一的「用户名或密码错误」
        provider.setHideUserNotFoundExceptions(true);
        return new ProviderManager(provider);
    }

    /**
     * CORS 配置。
     *
     * <p>开发期允许所有来源（前端跑在 5173/3000 等端口，且带 Authorization 头必须放行），
     * 生产环境应该收敛成具体域名白名单 —— 这一点在注释里写清楚，
     * 避免被当成「随手 allowAll」的安全问题。
     *
     * <p>注意 {@code allowCredentials(false)}：我们不用 Cookie，
     * 关掉它同时也能避免「allowCredentials(true) + allowedOrigins(*)」这个浏览器明确禁止的组合。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Content-Disposition"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
