package com.yit.rag.repository.system;

import com.yit.rag.domain.system.SysPermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 权限仓储。
 *
 * <p>权限点是全局的（没有 tenant_id），但「角色拥有哪些权限」是按角色的，
 * 所以真正判定权限时要走 roleId 集合去查关联表。
 */
public interface SysPermissionRepository extends JpaRepository<SysPermission, Long> {

    List<SysPermission> findByModule(String module);
}
