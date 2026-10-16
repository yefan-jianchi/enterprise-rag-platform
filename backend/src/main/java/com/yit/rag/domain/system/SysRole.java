package com.yit.rag.domain.system;

import com.yit.rag.domain.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicInsert;

/**
 * 角色（对应 {@code sys_role}）。
 *
 * <p>这个类解决什么问题：RBAC 的中间层，把「用户」与「权限」解耦。
 * {@code tenant_id = 0} 是系统内置角色（ADMIN / KNOWLEDGE_MANAGER / AGENT / VIEWER），
 * 对所有租户可见；租户也可以自建角色（tenant_id = 自己的 ID）。
 */
@Getter
@Setter
@Entity
@DynamicInsert
@Table(name = "sys_role")
public class SysRole extends CreatedEntity {

    /** 0 表示系统内置角色 */
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 0L;

    /** ADMIN / KNOWLEDGE_MANAGER / AGENT / VIEWER */
    @Column(name = "role_code", nullable = false, length = 64)
    private String roleCode;

    @Column(name = "role_name", nullable = false, length = 64)
    private String roleName;

    @Column(name = "description", length = 255)
    private String description;
}
