package com.yit.rag.security;

import com.yit.rag.common.exception.BizException;
import com.yit.rag.domain.system.SysRole;
import com.yit.rag.domain.system.SysUser;
import com.yit.rag.domain.system.SysUserRole;
import com.yit.rag.domain.system.Tenant;
import com.yit.rag.repository.system.SysRolePermissionRepository;
import com.yit.rag.repository.system.SysRoleRepository;
import com.yit.rag.repository.system.SysUserRepository;
import com.yit.rag.repository.system.SysUserRoleRepository;
import com.yit.rag.repository.system.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 用户认证信息加载器。
 *
 * <p>这个类解决什么问题：登录时 Spring Security 需要「按用户名拿到用户 + 密码哈希 + 权限」。
 * 难点在于我们多了「租户」这一维 —— 用户名只在租户内唯一，
 * 全局 {@code loadUserByUsername(username)} 是拿不到唯一用户的。
 *
 * <p>解决方案（也是多租户系统的标准做法）：把 username 编码成
 * {@code tenantCode:username} 的形式传进来。登录接口把 body 里的 tenantCode 拼上去，
 * 这里再拆开查。这样既满足 Spring Security 的接口约定，
 * 又不破坏「一个租户内用户名唯一」的数据库约束。
 *
 * <p>权限的解析路径（一次查完，避免 N+1）：
 * <pre>
 *   sys_user ──(user_id)──▶ sys_user_role ──▶ sys_role（取 roleCode）
 *                                    └──(role_id)──▶ sys_role_permission ──▶ sys_permission（取 permCode）
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    /** 租户与用户名的分隔符，选冒号是因为它不可能出现在租户编码里 */
    public static final String TENANT_SEPARATOR = ":";

    private final SysUserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final SysRoleRepository roleRepository;
    private final SysRolePermissionRepository rolePermissionRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String login) throws UsernameNotFoundException {
        String[] parts = splitLogin(login);
        String tenantCode = parts[0];
        String username = parts[1];

        Tenant tenant = tenantRepository.findByTenantCode(tenantCode)
                .orElseThrow(() -> new UsernameNotFoundException("租户不存在: " + tenantCode));

        SysUser user = userRepository.findByTenantIdAndUsername(tenant.getId(), username)
                .orElseThrow(() -> new UsernameNotFoundException("用户名或密码错误"));

        Set<String> roles = new LinkedHashSet<>(userRoleRepository.findRoleCodesByUserId(user.getId()));
        Set<String> permissions = loadPermissions(user.getId());
        return new SecurityUser(user, tenant.getTenantCode(), roles, permissions);
    }

    /**
     * 查询某用户的所有权限码。
     */
    @Transactional(readOnly = true)
    public Set<String> loadPermissions(Long userId) {
        List<Long> roleIds = userRoleRepository.findByLeftId(userId).stream()
                .map(SysUserRole::getRightId)
                .toList();
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(rolePermissionRepository.findPermCodesByRoleIds(roleIds));
    }

    /**
     * 查询某用户的角色码（登录响应里要返回给前端做菜单渲染）。
     */
    @Transactional(readOnly = true)
    public Set<String> loadRoles(Long userId) {
        return new LinkedHashSet<>(userRoleRepository.findRoleCodesByUserId(userId));
    }

    /**
     * 按租户编码 + 用户名取用户实体（登录流程用）。
     */
    @Transactional(readOnly = true)
    public SysUser findUser(String tenantCode, String username) {
        Tenant tenant = tenantRepository.findByTenantCode(tenantCode)
                .orElseThrow(() -> BizException.badRequest("租户不存在: " + tenantCode));
        if (!tenant.isUsable()) {
            throw BizException.forbidden("该租户已停用或已到期，请联系管理员");
        }
        return userRepository.findByTenantIdAndUsername(tenant.getId(), username)
                .orElseThrow(() -> BizException.unauthorized("用户名或密码错误"));
    }

    /** 按 id 查租户（构造 JWT 时需要 tenantCode） */
    @Transactional(readOnly = true)
    public Tenant findTenant(Long tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> BizException.notFound("租户不存在: " + tenantId));
    }

    /** 取租户下的内置角色（用于管理员引导时绑定 ADMIN 角色） */
    @Transactional(readOnly = true)
    public SysRole findBuiltinRole(String roleCode) {
        // tenant_id = 0 是系统内置角色
        return roleRepository.findByTenantIdAndRoleCode(0L, roleCode)
                .orElseThrow(() -> BizException.notFound("系统内置角色不存在: " + roleCode));
    }

    /**
     * 把登录名拆成 [租户编码, 用户名]。
     *
     * <p>只带用户名时（例如/actuator 或测试场景）默认落到 {@code demo} 租户，
     * 避免直接抛异常导致排查困难。
     */
    private String[] splitLogin(String login) {
        if (login == null || login.isBlank()) {
            throw new UsernameNotFoundException("登录名不能为空");
        }
        int idx = login.indexOf(TENANT_SEPARATOR);
        if (idx <= 0 || idx == login.length() - 1) {
            // 没带租户前缀：按默认租户处理（保持接口可用性）
            return new String[]{"demo", login};
        }
        return new String[]{login.substring(0, idx), login.substring(idx + 1)};
    }
}
