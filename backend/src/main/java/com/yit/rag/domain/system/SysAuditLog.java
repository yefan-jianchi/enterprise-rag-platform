package com.yit.rag.domain.system;

import com.yit.rag.domain.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicInsert;

/**
 * 操作审计日志（对应 {@code sys_audit_log}）。
 *
 * <p>这个类解决什么问题：回答「谁在什么时候对什么资源做了什么、成功没有」。
 * 面向企业的系统里，审计日志是合规刚需（等保、ISO 27001 都会查）。
 *
 * <p>设计取舍：写日志绝不能影响主流程 —— 所以审计写入放在
 * {@code AuditService} 里做 try-catch 吞异常 + WARN 日志，
 * 不允许因为「日志表插入失败」导致业务回滚。
 */
@Getter
@Setter
@Entity
@DynamicInsert
@Table(name = "sys_audit_log")
public class SysAuditLog extends CreatedEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 0L;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "username", length = 64)
    private String username;

    /** 动作：LOGIN / DOC_UPLOAD / DOC_DELETE / KB_CREATE / MODEL_EDIT ... */
    @Column(name = "action", nullable = false, length = 64)
    private String action;

    /** 资源标识，如 kb:1 / doc:12 */
    @Column(name = "resource", length = 128)
    private String resource;

    @Column(name = "detail", length = 1000)
    private String detail;

    @Column(name = "ip", length = 64)
    private String ip;

    /** 1 成功 / 0 失败 */
    @Column(name = "success", nullable = false)
    private Integer success = 1;
}
