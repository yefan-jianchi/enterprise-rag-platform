package com.yit.rag.service.kb;

import com.yit.rag.api.dto.kb.KbDtos;
import com.yit.rag.common.exception.BizException;
import com.yit.rag.common.tenant.TenantContext;
import com.yit.rag.common.util.CryptoUtil;
import com.yit.rag.domain.kb.KnowledgeBase;
import com.yit.rag.domain.kb.KnowledgeDocument;
import com.yit.rag.repository.kb.KbChunkRepository;
import com.yit.rag.repository.kb.KnowledgeDocumentRepository;
import com.yit.rag.service.ai.AiServiceClient;
import com.yit.rag.service.storage.FileStorageService;
import com.yit.rag.service.system.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 文档服务 —— 文档的上传、查询、重建索引、删除。
 *
 * <p>这个类解决什么问题：把「文档」这个业务对象的操作入口收敛到一处，
 * 并把耗时的处理流水线交给 {@link DocumentProcessService} 异步执行。
 *
 * <p><b>为什么处理逻辑不在这个类里？</b>（Spring 的关键细节，面试常问）
 * {@code @Async} 依赖动态代理。如果在这里直接调用 {@code this.processAsync()}，
 * 属于自调用、不经过代理，{@code @Async} 会<b>完全失效</b>，
 * 上传接口将同步阻塞到索引建完（大文件几十秒）。
 * 所以处理逻辑必须放在独立的 Bean 里（见 {@link DocumentProcessService}），
 * 跨 Bean 调用才走代理。
 *
 * <p>状态机（由处理服务推进，这里只负责发起与查询）：
 * <pre>
 *   PENDING ──▶ PARSING ──▶ CHUNKING ──▶ EMBEDDING ──▶ INDEXED
 *      └───────────── 任意阶段异常 ─────────────▶ FAILED(带 errorMsg)
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    /** 支持的文档类型（与 AI 服务的 parser 能力对齐） */
    private static final Set<String> SUPPORTED_TYPES = Set.of("pdf", "docx", "doc", "md", "markdown", "txt");

    private final KnowledgeDocumentRepository docRepository;
    private final KbChunkRepository chunkRepository;
    private final KnowledgeBaseService kbService;
    private final DocumentProcessService processService;
    private final FileStorageService storageService;
    private final AiServiceClient aiServiceClient;
    private final AuditService auditService;

    // ==================================================================
    //  上传
    // ==================================================================

    /**
     * 上传文档：落文件 + 落库 + 触发异步处理，立即返回。
     *
     * @return 刚落库的文档（状态 PENDING），前端拿 id 轮询状态
     */
    @Transactional
    public KbDtos.DocListItem upload(Long kbId, MultipartFile file) {
        Long tenantId = TenantContext.requireTenantId();
        Long userId = TenantContext.getUserIdOrNull();

        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("上传文件不能为空");
        }
        KnowledgeBase kb = kbService.requireKb(tenantId, kbId);

        String originalName = file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename();
        String docType = resolveDocType(originalName);
        if (!SUPPORTED_TYPES.contains(docType)) {
            throw BizException.badRequest("不支持的文件类型: " + docType
                    + "（仅支持 pdf / docx / doc / md / txt）");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException("读取上传文件失败: " + e.getMessage(), e);
        }
        String fileHash = CryptoUtil.sha256Hex(bytes);

        // 对象键：tenant/{租户}/kb/{知识库}/{hash 前 12 位}_{文件名}
        // 用 hash 前缀避免同名文件互相覆盖；保留文件名便于人工排查
        String objectKey = String.format("tenant/%d/kb/%d/%s_%s",
                tenantId, kbId, fileHash.substring(0, 12), sanitizeFileName(originalName));

        // 落文件（local 实现写磁盘；minio 实现上传对象），两种实现对上层完全透明
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            storageService.save(objectKey, in, bytes.length, file.getContentType());
        } catch (IOException e) {
            throw new BizException("保存文件失败: " + e.getMessage(), e);
        }

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setTenantId(tenantId);
        doc.setKbId(kbId);
        doc.setDocName(originalName);
        doc.setDocType(docType);
        doc.setFileKey(objectKey);
        doc.setFileSize((long) bytes.length);
        doc.setFileHash(fileHash);
        doc.setStatus(KnowledgeDocument.Status.PENDING);
        doc.setChunkCount(0);
        doc.setCreatedBy(userId);
        doc = docRepository.save(doc);

        auditService.log(AuditService.ACTION_DOC_UPLOAD, "doc:" + doc.getId(),
                "上传文档 " + originalName + " (" + bytes.length + " bytes) 到知识库 " + kb.getKbName());

        log.info("文档已入库待处理: id={} name={} kbId={} size={}B hash={}",
                doc.getId(), originalName, kbId, bytes.length, fileHash.substring(0, 12));

        // 交给独立 Bean 异步处理（跨 Bean 调用才能让 @Async 生效）。
        // 分块参数从知识库上取出来一起传下去 —— 异步线程里拿不到 ThreadLocal 与实体。
        processService.process(doc.getId(), tenantId, kb.getChunkSize(), kb.getChunkOverlap());

        return toItem(doc);
    }

    // ==================================================================
    //  查询
    // ==================================================================

    @Transactional(readOnly = true)
    public List<KbDtos.DocListItem> listByKb(Long kbId) {
        Long tenantId = TenantContext.requireTenantId();
        kbService.requireKb(tenantId, kbId);   // 越权校验
        return docRepository.findByTenantIdAndKbIdOrderByIdDesc(tenantId, kbId)
                .stream().map(DocumentService::toItem).toList();
    }

    @Transactional(readOnly = true)
    public KbDtos.DocListItem get(Long docId) {
        Long tenantId = TenantContext.requireTenantId();
        KnowledgeDocument doc = docRepository.findByIdAndTenantId(docId, tenantId)
                .orElseThrow(() -> BizException.notFound("文档不存在或无权访问: " + docId));
        return toItem(doc);
    }

    @Transactional(readOnly = true)
    public List<KbDtos.ChunkItem> chunks(Long docId) {
        Long tenantId = TenantContext.requireTenantId();
        docRepository.findByIdAndTenantId(docId, tenantId)
                .orElseThrow(() -> BizException.notFound("文档不存在或无权访问: " + docId));
        return chunkRepository.findByTenantIdAndDocIdOrderByChunkIndexAsc(tenantId, docId)
                .stream()
                .map(c -> new KbDtos.ChunkItem(c.getId(), c.getChunkIndex(), c.getContent(),
                        c.getTokenCount(), c.getPageNo(), c.getCharStart(), c.getCharEnd()))
                .toList();
    }

    // ==================================================================
    //  重建索引 / 删除
    // ==================================================================

    /**
     * 重建索引（重新走一遍解析 → 分块 → 向量化）。
     *
     * <p>使用场景：换了 embedding 模型、调整了分块参数、或者索引文件损坏。
     */
    @Transactional
    public KbDtos.DocListItem reindex(Long docId) {
        Long tenantId = TenantContext.requireTenantId();
        KnowledgeDocument doc = docRepository.findByIdAndTenantId(docId, tenantId)
                .orElseThrow(() -> BizException.notFound("文档不存在或无权访问: " + docId));
        KnowledgeBase kb = kbService.requireKb(tenantId, doc.getKbId());

        // 复位状态，让前端立刻看到「重新处理中」
        doc.setStatus(KnowledgeDocument.Status.PENDING);
        doc.setErrorMsg(null);
        doc.setChunkCount(0);
        doc = docRepository.save(doc);

        auditService.log(AuditService.ACTION_DOC_REINDEX, "doc:" + docId, "重建索引: " + doc.getDocName());
        processService.process(docId, tenantId, kb.getChunkSize(), kb.getChunkOverlap());
        log.info("文档重建索引已触发: docId={} name={}", docId, doc.getDocName());
        return toItem(doc);
    }

    /**
     * 删除文档：先删向量索引，再删本地分块与记录，最后重建知识库索引。
     *
     * <p>顺序说明：先删外部资源（向量），后删本地数据。
     * 万一向量删除失败，本地记录还在，重试一次即可；
     * 反过来就会留下指向不存在 chunk 的孤儿向量，检索时命中脏数据。
     */
    @Transactional
    public void delete(Long docId) {
        Long tenantId = TenantContext.requireTenantId();
        KnowledgeDocument doc = docRepository.findByIdAndTenantId(docId, tenantId)
                .orElseThrow(() -> BizException.notFound("文档不存在或无权访问: " + docId));

        Long kbId = doc.getKbId();
        aiServiceClient.deleteDocIndex(kbId, docId);
        int removedChunks = chunkRepository.deleteByDoc(tenantId, docId);
        docRepository.delete(doc);

        auditService.log(AuditService.ACTION_DOC_DELETE, "doc:" + docId,
                "删除文档 " + doc.getDocName() + "，连带 " + removedChunks + " 个分块");

        // 用剩余分块重建知识库索引，保证删完之后检索结果里不再出现已删除的文档
        processService.rebuildKbIndex(tenantId, kbId);

        log.info("文档已删除: docId={} name={} 清理分块={}", docId, doc.getDocName(), removedChunks);
    }

    // ==================================================================
    //  工具方法
    // ==================================================================

    /** 由扩展名推断文档类型 */
    private String resolveDocType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            // 没有扩展名时按纯文本处理（例如 README）
            return "txt";
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return "markdown".equals(ext) ? "md" : ext;
    }

    /** 文件名清洗：去掉路径分隔符与危险字符，避免写文件时越界 */
    private String sanitizeFileName(String name) {
        String base = name.replace('\\', '_').replace('/', '_').trim();
        base = base.replaceAll("[<>:\"|?*\\x00-\\x1f]", "_");
        return base.length() > 120 ? base.substring(base.length() - 120) : base;
    }

    private static KbDtos.DocListItem toItem(KnowledgeDocument d) {
        return new KbDtos.DocListItem(
                d.getId(), d.getKbId(), d.getDocName(), d.getDocType(), d.getFileSize(),
                d.getStatus(), d.getChunkCount(), d.getErrorMsg(),
                d.getParsedAt(), d.getIndexedAt(), d.getCreatedAt());
    }
}
