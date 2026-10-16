package com.yit.rag.repository.system;

import com.yit.rag.domain.system.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 用户仓储。
 *
 * <p>所有查询都带 tenantId：多租户系统里，「按用户名查用户」必须先把租户圈定，
 * 否则 demo 租户的 admin 和 acme 租户的 admin 就会互相串。
 */
public interface SysUserRepository extends JpaRepository<SysUser, Long> {

    Optional<SysUser> findByTenantIdAndUsername(Long tenantId, String username);

    boolean existsByTenantIdAndUsername(Long tenantId, String username);
}
