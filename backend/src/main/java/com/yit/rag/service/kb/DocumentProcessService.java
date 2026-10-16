package com.yit.rag.service.kb;

import com.yit.rag.common.exception.BizException;
import com.yit.rag.domain.kb.KbChunk;
import com.yit.rag.domain.kb.KnowledgeDocument;
import com.yit.rag.repository.kb.KbChunkRepository;
import com.yit.rag.repository.kb.KnowledgeDocumentRepository;
import com.yit.rag.service.ai.AiServiceClient;
import com.yit.rag.service.ai.dto.AiDtos;
import com.yit.rag.service.storage.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档处理流水线的执行器。
 *
 * <p><b>为什么单独一个类？</b>这是个必须讲清楚的 Spring 细节：
 * {@code @Async} 是靠<b>动态代理</b>生效的。如果 {@code DocumentService.upload()}
 * 直接调用自己类里的 {@code processAsync()}，那是 {@code this} 调用，
 * 不经过代理 —— {@code @Async} 完全失效，代码会同步执行！
 * 结果是上传接口会一直阻塞到文档索引建完（大文件几十秒），
 * 而且日志会显示处理逻辑跑在 Tomcat 线程（http-nio-8080-exec-N）而不是 doc-process-N。
 *
 * <p>所以把「异步处理逻辑」抽到独立的 Bean 里，由 DocumentService 注入并调用。
 * 这样调用会经过 Spring 代理，{@code @Async("documentExecutor")} 才真正生效。
 *
 * <p>状态机的推进都在这里完成，每个状态方法用 {@code REQUIRES_NEW} 独立事务：
 * 如果整体包在一个事务里，失败时状态会回滚成 PENDING，
 * 前端就永远看不到 FAILED 和失败原因了。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentProcessService {

    private final KnowledgeDocumentRepository docRepository;
    private final KbChunkRepository chunkRepository;
    private final FileStorageService storageService;
    private final AiServiceClient aiServiceClient;

    /**
     * 自身的代理引用（Spring 的 self-injection）。
     *
     * <p><b>为什么需要它？</b>这是 Spring AOP 的经典陷阱：
     * {@code this.xxx()} 这种自调用不经过代理，所以 {@code @Transactional}
     * 和 {@code @Async} 都不会生效。这里的 {@link #process} 方法本身<b>不能</b>加
     * {@code @Transactional}（否则整个耗时流程会占住一个数据库连接几十秒），
     * 但中间的「删旧分块 + 批量插入」必须在一个事务里。
     * 所以注入自己的代理，通过 {@code self.replaceChunks(...)} 调用，
     * 事务切面才会生效。
     */
    private final org.springframework.beans.factory.ObjectProvider<DocumentProcessService> selfProvider;

    /** 取自身代理（延迟解析，避免构造期循环依赖） */
    private DocumentProcessService self() {
        return selfProvider.getObject();
    }

    /**
     * 异步处理一份文档：解析 → 分块 → 向量化 → 建索引。
     *
     * <p>所有参数（tenantId / chunkSize / chunkOverlap）都由调用方显式传进来，
     * 因为异步线程里拿不到：
     * <ul>
     *   <li>{@code TenantContext} 是 ThreadLocal（拿不到租户）</li>
     *   <li>知识库实体属于持久化上下文（跨线程访问有懒加载风险）</li>
     * </ul>
     * 这既是技术要求，也是更清晰的接口设计：异步任务应该是自包含的。
     *
     * <p>注意这个方法<b>故意不加</b> {@code @Transactional}：
     * 整个流程包含多次 HTTP 调用（解析 + 建索引），可能耗时几十秒。
     * 如果包在一个事务里，数据库连接会被一直占着，并发几个大文档就把连接池耗尽。
     * 所以事务粒度被拆到各个阶段（见 {@link #replaceChunks} 与状态推进方法）。
     */
    @Async("documentExecutor")
    public void process(Long docId, Long tenantId, int chunkSize, int chunkOverlap) {
        log.info("[异步] 开始处理文档 docId={} tenantId={} chunkSize={} overlap={}",
                docId, tenantId, chunkSize, chunkOverlap);
        long start = System.currentTimeMillis();
        try {
            KnowledgeDocument doc = docRepository.findById(docId).orElse(null);
            if (doc == null) {
                log.warn("[异步] 文档不存在，可能已被删除: docId={}", docId);
                return;
            }
            if (!tenantId.equals(doc.getTenantId())) {
                // 防御性校验：理论上不会发生，但跨租户处理文档是严重事故，值得多一行判断
                throw new BizException("租户不匹配，拒绝处理该文档");
            }

            // ---------- 阶段 1：PARSING ----------
            updateStatus(docId, KnowledgeDocument.Status.PARSING);
            String filePath = storageService.absolutePath(doc.getFileKey());
            log.debug("[异步] 调 AI 服务解析: docId={} path={}", docId, filePath);
            AiDtos.ParseResponse parsed = aiServiceClient.parseDocument(filePath, docId, chunkSize, chunkOverlap);
            if (parsed.chunks() == null || parsed.chunks().isEmpty()) {
                throw new BizException("解析结果为空：文档可能没有可提取的文本内容");
            }
            updateParsed(docId, parsed.docType());

            // ---------- 阶段 2：CHUNKING ----------
            updateStatus(docId, KnowledgeDocument.Status.CHUNKING);
            List<KbChunk> chunks = new ArrayList<>(parsed.chunks().size());
            for (AiDtos.ParsedChunk c : parsed.chunks()) {
                KbChunk chunk = new KbChunk();
                chunk.setTenantId(tenantId);
                chunk.setKbId(doc.getKbId());
                chunk.setDocId(docId);
                chunk.setChunkIndex(c.chunkIndex() == null ? 0 : c.chunkIndex());
                chunk.setContent(c.content());
                chunk.setTokenCount(c.tokenCount() == null ? 0 : c.tokenCount());
                chunk.setPageNo(c.pageNo());
                chunk.setCharStart(c.charStart());
                chunk.setCharEnd(c.charEnd());
                chunks.add(chunk);
            }
            // 先清旧分块（重建场景），再批量插入 —— 必须在一个事务里，否则会短暂出现「没有分块」的中间态
            self().replaceChunks(tenantId, docId, chunks);
            log.info("[异步] 分块完成: docId={} 共 {} 块", docId, chunks.size());

            // ---------- 阶段 3：EMBEDDING ----------
            updateStatus(docId, KnowledgeDocument.Status.EMBEDDING);

            // ---------- 阶段 4：INDEXED ----------
            // rebuild=true：全量重建整个知识库的索引。
            // 为什么不做增量？向量索引与业务库的一致性是最容易出问题的地方；
            // 全量重建在几百个分块的量级下成本可接受，却换来「索引与数据库永远一致」的强保证。
            List<AiDtos.IndexChunk> indexChunks = buildIndexChunks(tenantId, doc.getKbId(), docId, doc);
            AiDtos.IndexBuildResponse indexed = aiServiceClient.buildIndex(doc.getKbId(), indexChunks, true);
            markIndexed(docId, parsed.chunkCount() == null ? chunks.size() : parsed.chunkCount());

            log.info("[异步] 文档处理完成: docId={} 分块={} 知识库向量总数={} 耗时={}ms",
                    docId, chunks.size(), indexed == null ? null : indexed.totalChunks(),
                    System.currentTimeMillis() - start);

        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("[异步] 文档处理失败: docId={} 原因={} 耗时={}ms",
                    docId, reason, System.currentTimeMillis() - start, e);
            markFailed(docId, reason);
        }
    }

    /**
     * 用知识库当前的分块重建一次索引（删除文档后调用）。
     *
     * <p>不做 {@code @Async}：删除接口需要「删完之后索引立刻是干净的」，
     * 否则用户刷新检索还会命中已删除的文档，体验和正确性都有问题。
     */
    public void rebuildKbIndex(Long tenantId, Long kbId) {
        try {
            List<AiDtos.IndexChunk> indexChunks = buildIndexChunks(tenantId, kbId, null, null);
            if (indexChunks.isEmpty()) {
                log.debug("知识库 {} 已无分块，跳过索引重建", kbId);
                return;
            }
            aiServiceClient.buildIndex(kbId, indexChunks, true);
            log.info("知识库 {} 索引已重建，分块数={}", kbId, indexChunks.size());
        } catch (Exception e) {
            log.warn("重建知识库索引失败 kbId={}: {}", kbId, e.getMessage());
        }
    }

    // ==================================================================
    //  事务方法（通过 self() 调用，保证切面生效）
    // ==================================================================

    /**
     * 替换某文档的全部分块：先删旧的，再批量插入新的。
     *
     * <p>为什么要放在一个事务里：如果先删后插分成两个事务，
     * 中间会存在「该文档没有任何分块」的时间窗。此时如果有检索请求打进来，
     * 会因为向量索引里还留着旧 chunkId 而出现「检索到已删除的分块」的问题。
     * 一个事务保证外部要么看到旧数据、要么看到新数据。
     *
     * <p>注意 {@code deleteByDoc} 是 {@code @Modifying} 的 JPQL 批量删除，
     * <b>必须</b>在事务中执行，否则 Hibernate 会抛
     * {@code TransactionRequiredException: Executing an update/delete query}
     * （这正是第一次联调时踩到的坑）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void replaceChunks(Long tenantId, Long docId, List<KbChunk> chunks) {
        chunkRepository.deleteByDoc(tenantId, docId);
        chunkRepository.saveAll(chunks);
    }

    // ==================================================================
    //  状态推进
    // ==================================================================

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStatus(Long docId, String status) {
        docRepository.findById(docId).ifPresent(d -> {
            d.setStatus(status);
            docRepository.save(d);
            log.debug("文档状态推进: docId={} -> {}", docId, status);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateParsed(Long docId, String docType) {
        docRepository.findById(docId).ifPresent(d -> {
            d.setParsedAt(LocalDateTime.now());
            if (docType != null && !docType.isBlank()) {
                d.setDocType(docType);
            }
            docRepository.save(d);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markIndexed(Long docId, int chunkCount) {
        docRepository.findById(docId).ifPresent(d -> {
            d.setStatus(KnowledgeDocument.Status.INDEXED);
            d.setChunkCount(chunkCount);
            d.setErrorMsg(null);
            d.setIndexedAt(LocalDateTime.now());
            docRepository.save(d);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long docId, String reason) {
        docRepository.findById(docId).ifPresent(d -> {
            d.markFailed(reason);
            docRepository.save(d);
            log.warn("文档标记为 FAILED: docId={} 原因={}", docId, d.getErrorMsg());
        });
    }

    // ==================================================================
    //  内部
    // ==================================================================

    /**
     * 把知识库下的分块组装成 AI 服务需要的建索引入参。
     *
     * @param currentDocId 当前正在处理的文档 id（避免重复查库）
     * @param currentDoc   当前文档实体（可为 null）
     */
    private List<AiDtos.IndexChunk> buildIndexChunks(Long tenantId, Long kbId,
                                                     Long currentDocId, KnowledgeDocument currentDoc) {
        List<KbChunk> allChunks = chunkRepository.findByTenantIdAndKbIdOrderByIdAsc(tenantId, kbId);
        List<AiDtos.IndexChunk> indexChunks = new ArrayList<>(allChunks.size());
        for (KbChunk c : allChunks) {
            KnowledgeDocument owner;
            if (currentDoc != null && c.getDocId().equals(currentDocId)) {
                owner = currentDoc;
            } else {
                owner = docRepository.findByIdAndTenantId(c.getDocId(), tenantId).orElse(null);
            }
            indexChunks.add(new AiDtos.IndexChunk(
                    c.getId(),
                    c.getDocId(),
                    owner == null ? ("文档" + c.getDocId()) : owner.getDocName(),
                    c.getChunkIndex(),
                    c.getContent(),
                    c.getPageNo()));
        }
        return indexChunks;
    }
}
