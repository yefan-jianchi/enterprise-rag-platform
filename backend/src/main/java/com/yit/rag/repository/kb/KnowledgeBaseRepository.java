package com.yit.rag.repository.kb;

import com.yit.rag.domain.kb.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 知识库仓储。
 *
 * <p>注意 {@code findByIdAndTenantId} 这种「主键 + 租户」双条件查询：
 * 这是多租户下的标准姿势 —— 只按 id 查会让攻击者换个 id 就读到别人的知识库（水平越权）。
 */
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    List<KnowledgeBase> findByTenantIdOrderByIdDesc(Long tenantId);

    Optional<KnowledgeBase> findByIdAndTenantId(Long id, Long tenantId);

    long countByTenantId(Long tenantId);

    /**
     * 批量取知识库引用（只查 id，不加载实体）。
     *
     * <p>用于对话场景：给定一个 kbId 列表，快速过滤出「属于当前租户」的那些。
     * 为什么需要一个只查 id 的方法：对话接口只需要判断「这个知识库能不能用」，
     * 不需要它的全部字段。加载整个实体会白白产生一堆 SQL 和对象分配。
     */
    @Query("select kb.id from KnowledgeBase kb where kb.tenantId = :tenantId and kb.id in :ids")
    List<Long> findIdsByTenantIdAndIdIn(@Param("tenantId") Long tenantId, @Param("ids") List<Long> ids);
}
