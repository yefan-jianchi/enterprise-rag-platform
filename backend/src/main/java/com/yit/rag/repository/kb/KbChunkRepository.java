package com.yit.rag.repository.kb;

import com.yit.rag.domain.kb.KbChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 分块仓储。
 *
 * <p>分块表是数据量最大的一张（一个文档几十到几百块），所以：
 * <ul>
 *   <li>查分块列表按 (doc_id, chunk_index) 走索引，保证顺序稳定</li>
 *   <li>删除文档时用一条批量 DELETE 清分块，不要逐个 remove（会产生 N 条 SQL）</li>
 *   <li>建索引时要把某知识库下所有分块捞出来喂给 AI 服务</li>
 * </ul>
 */
public interface KbChunkRepository extends JpaRepository<KbChunk, Long> {

    List<KbChunk> findByTenantIdAndDocIdOrderByChunkIndexAsc(Long tenantId, Long docId);

    List<KbChunk> findByTenantIdAndKbIdOrderByIdAsc(Long tenantId, Long kbId);

    long countByTenantIdAndKbId(Long tenantId, Long kbId);

    long countByTenantIdAndDocId(Long tenantId, Long docId);

    /** 批量删除某文档的所有分块（重建索引前先清干净） */
    @Modifying
    @Query("delete from KbChunk c where c.docId = :docId and c.tenantId = :tenantId")
    int deleteByDoc(@Param("tenantId") Long tenantId, @Param("docId") Long docId);

    /** 批量删除某知识库的所有分块（删除知识库时用） */
    @Modifying
    @Query("delete from KbChunk c where c.kbId = :kbId and c.tenantId = :tenantId")
    int deleteByKb(@Param("tenantId") Long tenantId, @Param("kbId") Long kbId);
}
