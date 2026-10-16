package com.yit.rag.api.dto.auth;

import java.util.List;

/**
 * 登录响应体（{@code /api/auth/login} 与 {@code /api/auth/me} 共用）。
 *
 * <p>字段严格遵循前端契约，不要改名字 —— 前端已经按这个结构解析了。
 *
 * <p>为什么不直接把 JWT 字符串作为响应体：前端需要立刻知道
 * 「我是谁、属于哪个租户、有哪些角色和权限」，才能渲染菜单、控制按钮显隐。
 * 如果只返回 token，前端还得再调一次 /me，多一次往返。
 * 把 user 一起返回是「一次请求拿到足够信息」的实用性设计。
 */
public record LoginResponse(
        /** JWT 令牌 */
        String token,
        /** 用户信息 */
        UserInfo user
) {

    /**
     * 用户信息。
     *
     * <p>注意这里<b>不含</b> tenantCode 之外的敏感字段，也不含密码哈希 ——
     * 响应 DTO 与实体分离的好处：不会因为「实体加了个字段」就意外泄露出去。
     */
    public record UserInfo(
            Long id,
            String username,
            String nickname,
            Long tenantId,
            String tenantCode,
            List<String> roles,
            List<String> permissions
    ) {
    }
}
