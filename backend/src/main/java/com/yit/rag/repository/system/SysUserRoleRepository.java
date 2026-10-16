package com.yit.rag.repository.system;

import com.yit.rag.domain.IdPair;
import com.yit.rag.domain.system.SysUserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 用户-角色关联仓储。
 *
 * <p>登录/取当前用户时要一次性把「用户的所有角色码」和「所有权限码」查出来塞进 JWT，
 * 所以这里提供两条 join 查询，避免 N+1。
 */
public interface SysUserRoleRepository extends JpaRepository<SysUserRole, IdPair> {

    List<SysUserRole> findByLeftId(Long userId);

    /** 直接取角色码，省去一次转换 */
    @Query("""
            select r.roleCode from SysRole r
            where r.id in (select ur.rightId from SysUserRole ur where ur.leftId = :userId)
            order by r.id
            """)
    List<String> findRoleCodesByUserId(@Param("userId") Long userId);

    boolean existsByLeftIdAndRightId(Long userId, Long roleId);
}
