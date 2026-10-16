package com.yit.rag.repository.system;

import com.yit.rag.domain.IdPair;
import com.yit.rag.domain.system.SysRolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 角色-权限关联仓储。
 *
 * <p>给定用户的角色集合，一条 join 查出全部权限码 —— 这是 RBAC 里最高频的查询，
 * 必须一次查完（JWT 里要放权限码，不能每个权限查一次库）。
 */
public interface SysRolePermissionRepository extends JpaRepository<SysRolePermission, IdPair> {

    @Query("""
            select distinct p.permCode from SysPermission p
            where p.id in (select rp.rightId from SysRolePermission rp where rp.leftId in :roleIds)
            order by p.permCode
            """)
    List<String> findPermCodesByRoleIds(@Param("roleIds") List<Long> roleIds);

    List<SysRolePermission> findByLeftId(Long roleId);
}
