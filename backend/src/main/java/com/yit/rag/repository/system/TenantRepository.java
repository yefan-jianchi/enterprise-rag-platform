package com.yit.rag.repository.system;

import com.yit.rag.domain.system.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 租户仓储。
 *
 * <p>登录时按 {@code tenantCode} 定位租户，所以只需要一个按编码查询的方法。
 */
public interface TenantRepository extends JpaRepository<Tenant, Long> {

    Optional<Tenant> findByTenantCode(String tenantCode);
}
