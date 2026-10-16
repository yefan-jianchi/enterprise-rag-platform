package com.yit.rag.common.tenant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 当前请求的租户/用户上下文。
 *
 * <p>这个类解决什么问题：多租户系统里，几乎每个 Service 方法都需要知道
 * 「现在是谁在操作、属于哪个租户」。如果一路用方法参数往下传，
 * 签名会变得又长又容易漏传 —— 漏传一次就是跨租户数据泄露。
 * 所以这里做成请求级 ThreadLocal：过滤器解析 JWT 后写入，
 * Service/Repository 随时取用，请求结束由拦截器 remove，避免线程池复用导致串租户。
 *
 * <p>关键安全约束：只有 {@code tenantId != null} 时才允许拼 tenant_id 过滤条件；
 * 拿不到就抛异常而不是「不过滤」，这样永远不会出现「忘了加条件」的全表查询。
 */
public final class TenantContext {

    /** 跨租户放行的特殊租户 ID：仅平台级运维使用 */
    public static final long PLATFORM_TENANT_ID = 0L;

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * 写入当前请求的用户上下文（由 JwtAuthenticationFilter 调用）。
     */
    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    /**
     * 取当前用户上下文，可能为 null（例如 /api/auth/login 这类匿名接口）。
     */
    public static CurrentUser get() {
        return HOLDER.get();
    }

    /**
     * 取当前租户 ID；拿不到直接抛异常。
     *
     * <p>刻意设计成抛异常而不是返回 null：让「忘记鉴权」在开发期就暴露，
     * 而不是在生产环境变成数据越权。
     */
    public static Long requireTenantId() {
        CurrentUser u = HOLDER.get();
        if (u == null || u.getTenantId() == null) {
            throw new IllegalStateException("当前请求没有租户上下文：请检查 JWT 过滤器是否生效");
        }
        return u.getTenantId();
    }

    /**
     * 取当前租户 ID，允许为空（用于「可选登录」的接口，如公开的知识库检索）。
     */
    public static Long getTenantIdOrNull() {
        CurrentUser u = HOLDER.get();
        return u == null ? null : u.getTenantId();
    }

    /** 取当前用户 ID，可能为 null */
    public static Long getUserIdOrNull() {
        CurrentUser u = HOLDER.get();
        return u == null ? null : u.getUserId();
    }

    /** 取当前用户名，可能为 null */
    public static String getUsernameOrNull() {
        CurrentUser u = HOLDER.get();
        return u == null ? null : u.getUsername();
    }

    /** 是否已登录 */
    public static boolean isAuthenticated() {
        return HOLDER.get() != null;
    }

    /**
     * 清空上下文。必须放在 finally 里调用。
     *
     * <p>Tomcat 线程是复用的，不清理会把 A 请求的租户带到 B 请求上 ——
     * 这是多租户系统最典型的事故来源。
     */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 当前用户快照（从 JWT 解析而来，不查库）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrentUser {
        private Long userId;
        private Long tenantId;
        private String tenantCode;
        private String username;
        private String nickname;
        /** 角色码，如 ADMIN */
        private Set<String> roles;
        /** 权限码，如 kb:doc:upload */
        private Set<String> permissions;

        public boolean hasPermission(String perm) {
            return permissions != null && permissions.contains(perm);
        }
    }
}
