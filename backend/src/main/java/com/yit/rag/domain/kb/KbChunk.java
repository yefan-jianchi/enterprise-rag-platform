package com.yit.rag.domain.kb;

import com.yit.rag.domain.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicInsert;

/**
 * 文档分块（对应 {@code kb_chunk}）—— RAG 检索的最小单元。
 *
 * <p>这个类解决什么问题：回答质量取决于「检索到的那一段」准不准，
 * 所以分块要带足溯源信息：
 * <ul>
 *   <li>{@code chunkIndex}：文档内第几块，用于「前后文扩展」</li>
 *   <li>{@code pageNo} + {@code charStart/charEnd}：能精确定位回原文页码与字符区间，
 *       前端可以做「点击引用跳转到 PDF 第 3 页并高亮」</li>
 *   <li>{@code vectorId}：在 FAISS IndexIDMap 里的主键，删除文档时靠它清向量</li>
 * </ul>
 */
@Getter
@Setter
@Entity
@DynamicInsert
@Table(name = "kb_chunk")
public class KbChunk extends CreatedEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "doc_id", nullable = false)
    private Long docId;

    /** 文档内序号，从 0 开始 */
    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    /** 分块正文（MEDIUMTEXT） */
    @Lob
    @Column(name = "content", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    /** token 数（按字符近似统计） */
    @Column(name = "token_count", nullable = false)
    private Integer tokenCount = 0;

    /** 所在页码，用于引用溯源 */
    @Column(name = "page_no")
    private Integer pageNo;

    @Column(name = "char_start")
    private Integer charStart;

    @Column(name = "char_end")
    private Integer charEnd;

    /** FAISS 中的向量主键 */
    @Column(name = "vector_id")
    private Long vectorId;
}
