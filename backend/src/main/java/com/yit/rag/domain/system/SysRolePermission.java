package com.yit.rag.domain.system;

import com.yit.rag.domain.IdPair;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 角色-权限关联（对应 {@code sys_role_permission}）。
 *
 * <p>这个类解决什么问题：把权限点授予角色。授权变更只动这张表，
 * 不需要改代码里任何 {@code @PreAuthorize} 注解。
 */
@Getter
@Setter
@Entity
@IdClass(IdPair.class)
@Table(name = "sys_role_permission")
public class SysRolePermission {

    @Id
    @Column(name = "role_id", nullable = false)
    private Long leftId;

    @Id
    @Column(name = "perm_id", nullable = false)
    private Long rightId;

    public SysRolePermission() {
    }

    public SysRolePermission(Long roleId, Long permId) {
        this.leftId = roleId;
        this.rightId = permId;
    }
}
