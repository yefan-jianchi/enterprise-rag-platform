package com.yit.rag.repository.eval;

import com.yit.rag.domain.eval.EvalRetrievalCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 检索评测集仓储。
 *
 * <p>这张表是「用数据证明检索策略有效」的基础设施：
 * 固定一批问题 + 期望命中的关键词，分别跑 VECTOR / BM25 / HYBRID_RRF / HYBRID_RERANK，
 * 把 Hit@1 / Hit@5 / MRR 写回表里，就能拿出一张「混合检索把 Hit@5 从 x 提到 y」的对比图。
 * 面试时比空口说「我们做了混合检索」有说服力得多。
 */
public interface EvalRetrievalCaseRepository extends JpaRepository<EvalRetrievalCase, Long> {

    List<EvalRetrievalCase> findByTenantIdAndKbIdOrderByIdAsc(Long tenantId, Long kbId);

    List<EvalRetrievalCase> findByTenantIdOrderByIdAsc(Long tenantId);
}
