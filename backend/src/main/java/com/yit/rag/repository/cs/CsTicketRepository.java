package com.yit.rag.repository.cs;

import com.yit.rag.domain.cs.CsTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 工单仓储。
 *
 * <p>工单号需要由后端生成，所以这里提供 {@code findFirstByTicketNoStartingWith} 来
 * 支撑「TK + 日期 + 序号」的取号逻辑（同一天内顺序递增）。
 */
public interface CsTicketRepository extends JpaRepository<CsTicket, Long> {

    List<CsTicket> findByTenantIdOrderByIdDesc(Long tenantId);

    Optional<CsTicket> findFirstByTenantIdAndTicketNoStartingWithOrderByTicketNoDesc(
            Long tenantId, String prefix);

    long countByTenantId(Long tenantId);
}
