package com.yit.rag.repository.cs;

import com.yit.rag.domain.cs.CsHandoff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 转人工记录仓储。
 *
 * <p>同一个会话可能有多次转人工（用户反复要求），所以取「最近一条」用 id 倒序。
 * 工具 {@code transferToHuman} 需要幂等：如果已经有一条 WAITING 记录，
 * 就不重复插，避免用户点两次生成两条待接单任务把坐席搞晕。
 */
public interface CsHandoffRepository extends JpaRepository<CsHandoff, Long> {

    List<CsHandoff> findByTenantIdAndSessionIdOrderByIdDesc(Long tenantId, Long sessionId);

    Optional<CsHandoff> findFirstByTenantIdAndSessionIdAndStatusOrderByIdDesc(
            Long tenantId, Long sessionId, String status);

    List<CsHandoff> findByTenantIdOrderByIdDesc(Long tenantId);
}
