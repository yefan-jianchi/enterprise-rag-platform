package com.yit.rag.domain.eval;

import com.yit.rag.domain.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicInsert;

import java.math.BigDecimal;

/**
 * 检索评测用例（对应 {@code eval_retrieval_case}）。
 *
 * <p>这个类解决什么问题：检索效果没法「感觉一下」，必须量化。
 * 每条用例定义一个问题和期望命中的关键词，跑完策略后把指标落库：
 * <ul>
 *   <li>{@code hitAt1 / hitAt5}：Top1 / Top5 是否命中期望关键词</li>
 *   <li>{@code mrr}：倒数排名，命中越靠前分越高</li>
 *   <li>{@code strategy}：本条成绩属于哪种策略，便于横向对比</li>
 * </ul>
 */
@Getter
@Setter
@Entity
@DynamicInsert
@Table(name = "eval_retrieval_case")
public class EvalRetrievalCase extends CreatedEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "question", nullable = false, length = 500)
    private String question;

    @Column(name = "expect_doc_id")
    private Long expectDocId;

    /** 期望命中关键词 */
    @Column(name = "expect_keyword", length = 200)
    private String expectKeyword;

    /** Top1 是否命中 */
    @Column(name = "hit_at_1")
    private Integer hitAt1;

    /** Top5 是否命中 */
    @Column(name = "hit_at_5")
    private Integer hitAt5;

    /** 倒数排名 MRR */
    @Column(name = "mrr", precision = 6, scale = 4)
    private BigDecimal mrr;

    /** VECTOR / BM25 / HYBRID_RRF / HYBRID_RERANK */
    @Column(name = "strategy", length = 32)
    private String strategy;
}
