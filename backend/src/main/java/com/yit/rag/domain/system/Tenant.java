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
 * 租户（对应 {@code sys_tenant}）。
 *
 * <p>这个类解决什么问题：整个平台是多租户 SaaS 形态，
 * 租户是数据隔离的根：所有业务表都挂 tenant_id。
 * 登录时用 {@code tenant_code} 定位租户，之后一切查询都被 tenant_id 约束。
 */
@Getter
@Setter
@Entity
@DynamicInsert
@Table(name = "sys_tenant")
public class Tenant extends AuditedEntity {

    /** 租户编码，登录时使用（唯一） */
    @Column(name = "tenant_code", nullable = false, length = 64)
    private String tenantCode;

    /** 租户名称 */
    @Column(name = "tenant_name", nullable = false, length = 128)
    private String tenantName;

    /** 1 启用 / 0 停用 */
    @Column(name = "status", nullable = false)
    private Integer status = 1;

    /** 到期时间，NULL 表示永久 */
    @Column(name = "expire_at")
    private LocalDateTime expireAt;

    /** 月度 token 配额，0 表示不限 */
    @Column(name = "quota_tokens", nullable = false)
    private Long quotaTokens = 0L;

    /** 是否可用 = 启用 且 未过期 */
    public boolean isUsable() {
        if (status == null || status != 1) {
            return false;
        }
        return expireAt == null || expireAt.isAfter(LocalDateTime.now());
    }
}
