package com.yit.rag.repository.system;

import com.yit.rag.domain.system.SysAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 审计日志仓储。
 *
 * <p>管理端分页查看审计日志，必须按 tenantId 过滤 —— 审计数据尤其敏感，
 * 不能让 A 租户看到 B 租户谁登录过。
 */
public interface SysAuditLogRepository extends JpaRepository<SysAuditLog, Long> {

    Page<SysAuditLog> findByTenantIdOrderByIdDesc(Long tenantId, Pageable pageable);

    long countByTenantId(Long tenantId);
}
