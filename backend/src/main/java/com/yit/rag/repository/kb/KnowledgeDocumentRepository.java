package com.yit.rag.repository.kb;

import com.yit.rag.domain.kb.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 文档仓储。
 *
 * <p>覆盖流水线需要的几种查询：
 * <ul>
 *   <li>按知识库列出文档（前端文档列表）</li>
 *   <li>按 (id, tenantId) 取单个，用于状态推进与越权防护</li>
 *   <li>按 hash 查重，实现「同一文件秒传/复用」</li>
 * </ul>
 */
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    List<KnowledgeDocument> findByTenantIdAndKbIdOrderByIdDesc(Long tenantId, Long kbId);

    Optional<KnowledgeDocument> findByIdAndTenantId(Long id, Long tenantId);

    /** 秒传：同租户同知识库下是否已有同样 hash 且已建好索引的文档 */
    Optional<KnowledgeDocument> findFirstByTenantIdAndKbIdAndFileHashAndStatus(
            Long tenantId, Long kbId, String fileHash, String status);

    long countByTenantIdAndKbId(Long tenantId, Long kbId);

    long countByTenantIdAndStatus(Long tenantId, String status);
}
