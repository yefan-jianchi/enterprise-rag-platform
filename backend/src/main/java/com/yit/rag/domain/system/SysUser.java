package com.yit.rag.domain.system;

import com.yit.rag.domain.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicInsert;

import java.time.LocalDateTime;

/**
 * 用户（对应 {@code sys_user}）。
 *
 * <p>这个类解决什么问题：承载登录主体。几个关键点：
 * <ul>
 *   <li>唯一约束是 {@code (tenant_id, username)} —— 不同租户可以有同名用户</li>
 *   <li>只存 BCrypt 哈希，绝不存明文（种子 SQL 里故意没有用户，由后端引导创建）</li>
 *   <li>{@code lastLoginAt} 用于审计与「僵尸账号」治理</li>
 * </ul>
 */
@Getter
@Setter
@Entity
@DynamicInsert
@Table(name = "sys_user")
public class SysUser extends AuditedEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "username", nullable = false, length = 64)
    private String username;

    /** BCrypt 哈希 */
    @Column(name = "password_hash", nullable = false, length = 128)
    private String passwordHash;

    @Column(name = "nickname", length = 64)
    private String nickname;

    @Column(name = "email", length = 128)
    private String email;

    /** 1 启用 / 0 禁用 */
    @Column(name = "status", nullable = false)
    private Integer status = 1;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;
}
