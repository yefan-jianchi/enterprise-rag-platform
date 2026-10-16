package com.yit.rag.domain.kb;

import com.yit.rag.domain.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicInsert;

import java.math.BigDecimal;

/**
 * 知识库（对应 {@code kb_knowledge_base}）。
 *
 * <p>这个类解决什么问题：知识库是 RAG 检索的物理边界 ——
 * 向量索引按 kb_id 独立存放，检索只在自己知识库内做。
 * 同时也是「分块参数」的载体：chunk_size / chunk_overlap 决定文档怎么切，
 * top_k / score_threshold 决定召回多少、多相似才要。
 * 这些参数放在库里而不是写死在代码里，才能针对不同语料调优。
 */
@Getter
@Setter
@Entity
@DynamicInsert
@Table(name = "kb_knowledge_base")
public class KnowledgeBase extends AuditedEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "kb_name", nullable = false, length = 128)
    private String kbName;

    @Column(name = "description", length = 512)
    private String description;

    /** Embedding 模型名，例如 mock-embed-384 */
    @Column(name = "embed_model", nullable = false, length = 64)
    private String embedModel = "mock-embed-384";

    /** 分块目标长度（按字符近似 token） */
    @Column(name = "chunk_size", nullable = false)
    private Integer chunkSize = 500;

    /** 相邻分块重叠长度，避免答案被切断 */
    @Column(name = "chunk_overlap", nullable = false)
    private Integer chunkOverlap = 80;

    /** 检索召回条数 */
    @Column(name = "top_k", nullable = false)
    private Integer topK = 5;

    /** 相似度阈值，低于此值视为不相关 */
    @Column(name = "score_threshold", nullable = false, precision = 5, scale = 4)
    private BigDecimal scoreThreshold = new BigDecimal("0.2000");

    @Column(name = "created_by")
    private Long createdBy;
}
