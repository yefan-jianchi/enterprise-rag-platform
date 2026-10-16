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
 * 用户-角色关联（对应 {@code sys_user_role}）。
 *
 * <p>这个类解决什么问题：一个用户可以兼多个角色（例如既是 KNOWLEDGE_MANAGER 又是 AGENT），
 * 权限取并集。用独立实体而不是 {@code @ManyToMany} 的好处是：
 * 关联关系可以独立查询、独立审计，也不会意外触发级联删除。
 *
 * <p>主键属性名 leftId / rightId 必须与 {@link IdPair} 的字段名一致。
 */
@Getter
@Setter
@Entity
@IdClass(IdPair.class)
@Table(name = "sys_user_role")
public class SysUserRole {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long leftId;

    @Id
    @Column(name = "role_id", nullable = false)
    private Long rightId;

    public SysUserRole() {
    }

    public SysUserRole(Long userId, Long roleId) {
        this.leftId = userId;
        this.rightId = roleId;
    }
}
