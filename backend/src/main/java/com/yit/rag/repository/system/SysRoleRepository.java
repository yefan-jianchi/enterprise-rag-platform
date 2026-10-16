package com.yit.rag.repository.system;

import com.yit.rag.domain.system.SysRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 角色仓储。
 *
 * <p>角色查询要同时覆盖「系统内置角色（tenant_id=0）」与「租户自建角色」，
 * 所以用一条显式的 JPQL 而不是派生查询，语义更清楚。
 */
public interface SysRoleRepository extends JpaRepository<SysRole, Long> {

    /** 系统内置角色（tenant_id = 0） */
    Optional<SysRole> findByTenantIdAndRoleCode(Long tenantId, String roleCode);

    /** 某租户可见的全部角色 = 内置 + 自建 */
    @Query("select r from SysRole r where r.tenantId = 0 or r.tenantId = :tenantId order by r.id")
    List<SysRole> findVisibleRoles(@Param("tenantId") Long tenantId);

    List<SysRole> findByRoleCodeIn(List<String> roleCodes);
}
