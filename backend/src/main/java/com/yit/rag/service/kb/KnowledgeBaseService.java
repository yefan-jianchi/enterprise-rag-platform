package com.yit.rag.service.kb;

import com.yit.rag.api.dto.kb.KbDtos;
import com.yit.rag.common.exception.BizException;
import com.yit.rag.common.tenant.TenantContext;
import com.yit.rag.domain.kb.KnowledgeBase;
import com.yit.rag.repository.kb.KbChunkRepository;
import com.yit.rag.repository.kb.KnowledgeBaseRepository;
import com.yit.rag.repository.kb.KnowledgeDocumentRepository;
import com.yit.rag.service.ai.AiServiceClient;
import com.yit.rag.service.system.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库服务。
 *
 * <p>这个类解决什么问题：知识库是 RAG 的物理隔离单位（向量索引按 kb_id 独立存放），
 * 所以增删改查都必须严格带租户过滤 —— 只按 id 查会让攻击者换个 id 就删掉别人的知识库。
 *
 * <p>列表接口为什么不直接用 JPA 返回实体：因为前端需要
 * {@code docCount / chunkCount} 两个统计值，而实体里没有。
 * 如果在实体上挂 {@code @Transient} 字段再逐个查询，会退化成 N+1（10 个知识库 = 20 次查询）。
 * 这里的做法是「先查知识库列表，再用聚合查询算统计」，数据量小时也可以接受，
 * 并且把逻辑集中在 Service，将来换缓存或改成一次 group by 都很容易。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository kbRepository;
    private final KnowledgeDocumentRepository docRepository;
    private final KbChunkRepository chunkRepository;
    private final AiServiceClient aiServiceClient;
    private final AuditService auditService;

    /**
     * 当前租户的知识库列表。
     */
    @Transactional(readOnly = true)
    public List<KbDtos.KbListItem> list() {
        Long tenantId = TenantContext.requireTenantId();
        List<KnowledgeBase> bases = kbRepository.findByTenantIdOrderByIdDesc(tenantId);
        List<KbDtos.KbListItem> items = new ArrayList<>(bases.size());
        for (KnowledgeBase kb : bases) {
            items.add(toItem(tenantId, kb));
        }
        log.debug("知识库列表: tenantId={} 共 {} 个", tenantId, items.size());
        return items;
    }

    /**
     * 新建知识库。
     *
     * <p>默认值：分块 500/重叠 80（约 500 字一块，是中文语料的常用起点）、
     * top_k=5、相似度阈值 0.2。这些值都可以后续按语料特点调，
     * 所以不写死在代码里而是用数据库默认值。
     */
    @Transactional
    public KbDtos.KbListItem create(KbDtos.CreateKbRequest req) {
        Long tenantId = TenantContext.requireTenantId();
        if (req.kbName() == null || req.kbName().isBlank()) {
            throw BizException.badRequest("知识库名称不能为空");
        }
        // 同名检查：避免建出一堆同名知识库导致选择困难
        boolean duplicated = kbRepository.findByTenantIdOrderByIdDesc(tenantId).stream()
                .anyMatch(k -> k.getKbName().equalsIgnoreCase(req.kbName().trim()));
        if (duplicated) {
            throw BizException.badRequest("已存在同名知识库: " + req.kbName());
        }

        KnowledgeBase kb = new KnowledgeBase();
        kb.setTenantId(tenantId);
        kb.setKbName(req.kbName().trim());
        kb.setDescription(req.description());
        kb.setEmbedModel(req.embedModel() == null || req.embedModel().isBlank()
                ? "mock-embed-384" : req.embedModel());
        kb.setChunkSize(req.chunkSize() == null || req.chunkSize() <= 0 ? 500 : req.chunkSize());
        kb.setChunkOverlap(req.chunkOverlap() == null || req.chunkOverlap() < 0 ? 80 : req.chunkOverlap());
        kb.setTopK(req.topK() == null || req.topK() <= 0 ? 5 : req.topK());
        kb.setScoreThreshold(new BigDecimal("0.2000"));
        kb.setCreatedBy(TenantContext.getUserIdOrNull());
        kb = kbRepository.save(kb);

        auditService.log(AuditService.ACTION_KB_CREATE, "kb:" + kb.getId(), "新建知识库: " + kb.getKbName());
        log.info("知识库已创建: id={} name={} tenantId={}", kb.getId(), kb.getKbName(), tenantId);
        return toItem(tenantId, kb);
    }

    /**
     * 删除知识库（连带清理文档、分块与向量索引）。
     *
     * <p>顺序很重要：先删向量索引（外部资源，失败可重试），
     * 再删本地数据（分块 → 文档 → 知识库）。
     * 如果反过来，本地数据删完但向量删失败了，就会留下「孤儿向量」——
     * 检索时命中一个已经不存在的 chunkId，属于很难查的数据不一致。
     */
    @Transactional
    public void delete(Long kbId) {
        Long tenantId = TenantContext.requireTenantId();
        KnowledgeBase kb = requireKb(tenantId, kbId);

        long docCount = docRepository.countByTenantIdAndKbId(tenantId, kbId);
        long chunkCount = chunkRepository.countByTenantIdAndKbId(tenantId, kbId);

        // 1) 先让 AI 服务清掉整个知识库的向量（逐个文档删）
        for (var doc : docRepository.findByTenantIdAndKbIdOrderByIdDesc(tenantId, kbId)) {
            aiServiceClient.deleteDocIndex(kbId, doc.getId());
        }
        // 2) 再清本地：分块 → 文档 → 知识库
        chunkRepository.deleteByKb(tenantId, kbId);
        docRepository.deleteAll(docRepository.findByTenantIdAndKbIdOrderByIdDesc(tenantId, kbId));
        kbRepository.delete(kb);

        auditService.log(AuditService.ACTION_KB_DELETE, "kb:" + kbId,
                "删除知识库 " + kb.getKbName() + "，连带 " + docCount + " 个文档 / " + chunkCount + " 个分块");
        log.info("知识库已删除: id={} name={} 文档={} 分块={}", kbId, kb.getKbName(), docCount, chunkCount);
    }

    /**
     * 取知识库（带租户校验），供其他 Service 复用。
     */
    @Transactional(readOnly = true)
    public KnowledgeBase requireKb(Long tenantId, Long kbId) {
        return kbRepository.findByIdAndTenantId(kbId, tenantId)
                .orElseThrow(() -> BizException.notFound("知识库不存在或无权访问: " + kbId));
    }

    /** 组装列表项（含统计值） */
    private KbDtos.KbListItem toItem(Long tenantId, KnowledgeBase kb) {
        long docCount = docRepository.countByTenantIdAndKbId(tenantId, kb.getId());
        long chunkCount = chunkRepository.countByTenantIdAndKbId(tenantId, kb.getId());
        return new KbDtos.KbListItem(
                kb.getId(),
                kb.getKbName(),
                kb.getDescription(),
                kb.getEmbedModel(),
                kb.getChunkSize(),
                kb.getChunkOverlap(),
                kb.getTopK(),
                kb.getScoreThreshold() == null ? null : kb.getScoreThreshold().doubleValue(),
                docCount,
                chunkCount,
                kb.getCreatedAt());
    }
}
