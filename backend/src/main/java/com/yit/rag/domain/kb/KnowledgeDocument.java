package com.yit.rag.domain.kb;

import com.yit.rag.domain.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicInsert;

import java.time.LocalDateTime;

/**
 * 知识库文档（对应 {@code kb_document}）。
 *
 * <p>这个类解决什么问题：它是「文档处理流水线」的状态载体。上传后不是立刻可用，
 * 而要经过解析 → 分块 → 向量化 → 建索引几个阶段，
 * 每个阶段都要让前端能看到进度、失败能看到原因，所以用 {@link Status} 表达状态机：
 *
 * <pre>
 *   PENDING ──▶ PARSING ──▶ CHUNKING ──▶ EMBEDDING ──▶ INDEXED
 *      └────────────── 任意阶段出错 ──────────────▶ FAILED
 * </pre>
 *
 * <p>{@code fileHash}（SHA-256）用于秒传与去重：同一个文件重复上传直接复用已有结果。
 */
@Getter
@Setter
@Entity
@DynamicInsert
@Table(name = "kb_document")
public class KnowledgeDocument extends AuditedEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /** 原始文件名 */
    @Column(name = "doc_name", nullable = false, length = 255)
    private String docName;

    /** pdf / docx / md / txt */
    @Column(name = "doc_type", nullable = false, length = 16)
    private String docType;

    /** 对象存储中的 key（local 实现下是相对路径） */
    @Column(name = "file_key", nullable = false, length = 512)
    private String fileKey;

    @Column(name = "file_size", nullable = false)
    private Long fileSize = 0L;

    /** SHA-256，用于秒传与去重 */
    @Column(name = "file_hash", length = 64)
    private String fileHash;

    /** 处理状态，取值见 {@link Status} */
    @Column(name = "status", nullable = false, length = 16)
    private String status = Status.PENDING;

    /** 分块数量 */
    @Column(name = "chunk_count", nullable = false)
    private Integer chunkCount = 0;

    /** 失败原因（截断到 1000 字符，对齐表定义） */
    @Column(name = "error_msg", length = 1000)
    private String errorMsg;

    @Column(name = "parsed_at")
    private LocalDateTime parsedAt;

    @Column(name = "indexed_at")
    private LocalDateTime indexedAt;

    @Column(name = "created_by")
    private Long createdBy;

    /**
     * 文档处理状态常量。
     *
     * <p>用常量类而不是 Java 枚举：因为库里存的是 VARCHAR，
     * 将来新增状态（例如 SKIPPED）不需要改枚举、不需要数据迁移。
     */
    public static final class Status {
        /** 已入库，等待处理 */
        public static final String PENDING = "PENDING";
        /** 正在解析（PDF/Word → 纯文本） */
        public static final String PARSING = "PARSING";
        /** 正在分块 */
        public static final String CHUNKING = "CHUNKING";
        /** 正在向量化 */
        public static final String EMBEDDING = "EMBEDDING";
        /** 已建好索引，可被检索 */
        public static final String INDEXED = "INDEXED";
        /** 处理失败 */
        public static final String FAILED = "FAILED";

        private Status() {
        }
    }

    /** 是否已可以被检索 */
    public boolean isIndexed() {
        return Status.INDEXED.equals(status);
    }

    /** 记录失败原因，超长自动截断（表字段 1000，留点余量） */
    public void markFailed(String reason) {
        this.status = Status.FAILED;
        String r = reason == null ? "未知错误" : reason;
        this.errorMsg = r.length() > 900 ? r.substring(0, 900) + "..." : r;
    }
}
