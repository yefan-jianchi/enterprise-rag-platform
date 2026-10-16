package com.yit.rag.repository.ai;

import com.yit.rag.domain.ai.AiModelProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 模型提供方仓储 —— 多模型适配层与 Fallback 的数据入口。
 *
 * <p>关键查询是 {@link #findAvailable(Long)}：按 {@code priority} 升序返回
 * 「平台级或本租户」且 enabled=1 的提供方。ModelRouter 拿到这个有序列表后，
 * 从前往后试，第一个成功的就是本次调用使用的模型，失败的写日志并继续 —— 这就是 Fallback。
 *
 * <p>排序刻意在 SQL 里定好（priority asc, id asc）：priority 相同时用 id 保证顺序稳定，
 * 否则同一份数据两次查询顺序可能不同，Fallback 行为就不可复现了。
 */
public interface AiModelProviderRepository extends JpaRepository<AiModelProvider, Long> {

    @Query("""
            select p from AiModelProvider p
            where p.enabled = 1 and (p.tenantId = 0 or p.tenantId = :tenantId)
            order by p.priority asc, p.id asc
            """)
    List<AiModelProvider> findAvailable(@Param("tenantId") Long tenantId);

    @Query("""
            select p from AiModelProvider p
            where (p.tenantId = 0 or p.tenantId = :tenantId)
            order by p.priority asc, p.id asc
            """)
    List<AiModelProvider> findAllVisible(@Param("tenantId") Long tenantId);

    @Query("""
            select p from AiModelProvider p
            where p.providerCode = :code and (p.tenantId = 0 or p.tenantId = :tenantId)
            order by p.tenantId desc, p.id asc
            """)
    List<AiModelProvider> findByCode(@Param("code") String code, @Param("tenantId") Long tenantId);

    /**
     * 管理端列表：平台级 + 本租户，按优先级排序。
     * 单个查询直接用 JpaRepository 自带的 findById 即可。
     */
}
