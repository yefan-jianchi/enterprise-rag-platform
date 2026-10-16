package com.yit.rag.security;

import com.yit.rag.domain.system.SysUser;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spring Security 的用户主体。
 *
 * <p>这个类解决什么问题：把「JPA 实体 {@link SysUser}」与「Spring Security 需要的
 * UserDetails」解耦。实体是用来做持久化的，UserDetails 是用来做认证的，
 * 硬把两者合成一个类（让实体 implements UserDetails）会带来两个问题：
 * <ul>
 *   <li>实体会被塞进 SecurityContext 并跨请求持有，一旦序列化/懒加载就是坑</li>
 *   <li>权限码是「查出来」的而不是实体字段，塞进实体语义不干净</li>
 * </ul>
 *
 * <p>权限的映射方式：把「角色码」和「权限码」<b>都</b>放进 authorities。
 * 好处是 {@code @PreAuthorize("hasAuthority('kb:doc:upload')")} 和
 * {@code hasRole('ADMIN')} 两种写法都能用：
 * <ul>
 *   <li>权限码如 {@code kb:doc:upload} → 直接作为 authority</li>
 *   <li>角色码如 {@code ADMIN} → 作为 {@code ROLE_ADMIN}（Spring 的 hasRole 约定前缀）</li>
 * </ul>
 */
@Getter
public class SecurityUser implements UserDetails {

    private final Long userId;
    private final Long tenantId;
    private final String tenantCode;
    private final String username;
    private final String nickname;
    private final String password;
    private final Integer status;
    private final Set<String> roles;
    private final Set<String> permissions;
    private final Set<GrantedAuthority> authorities;

    public SecurityUser(SysUser user, String tenantCode, Set<String> roles, Set<String> permissions) {
        this.userId = user.getId();
        this.tenantId = user.getTenantId();
        this.tenantCode = tenantCode;
        this.username = user.getUsername();
        this.nickname = user.getNickname();
        this.password = user.getPasswordHash();
        this.status = user.getStatus();
        this.roles = roles == null ? Set.of() : roles;
        this.permissions = permissions == null ? Set.of() : permissions;

        Set<GrantedAuthority> auths = new LinkedHashSet<>();
        // 权限码直接作为 authority，@PreAuthorize("hasAuthority('kb:doc:upload')") 用它
        this.permissions.stream()
                .filter(p -> p != null && !p.isBlank())
                .map(SimpleGrantedAuthority::new)
                .forEach(auths::add);
        // 角色码加 ROLE_ 前缀，hasRole('ADMIN') 用它
        this.roles.stream()
                .filter(r -> r != null && !r.isBlank())
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .forEach(auths::add);
        this.authorities = auths;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /** status = 1 才算启用；停用的账号不允许登录 */
    @Override
    public boolean isEnabled() {
        return status != null && status == 1;
    }

    /** 权限码集合的字符串形式，方便写进 JWT */
    public Set<String> permissionCodes() {
        return permissions.stream().filter(p -> p != null && !p.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
