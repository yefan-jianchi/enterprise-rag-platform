package com.yit.rag.domain.chat;

import com.yit.rag.domain.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicInsert;

/**
 * Prompt 模板（对应 {@code ai_prompt_template}）。
 *
 * <p>这个类解决什么问题：把「提示词」从代码里挪到数据库里，
 * 让运营/算法同学不发版就能调优提示词。模板用 {{变量}} 占位：
 * <pre>
 *   RAG_QA  : {{context}} {{question}}
 *   REWRITE : {{question}}
 *   HYDE    : {{question}}
 *   RERANK  : {{question}} {{candidates}}
 *   CS_REPLY: {{context}} {{question}}
 * </pre>
 * 后端管理端提供查询与启停接口，AI 服务侧实际渲染由 {@code render_template} 完成。
 */
@Getter
@Setter
@Entity
@DynamicInsert
@Table(name = "ai_prompt_template")
public class AiPromptTemplate extends CreatedEntity {

    /** 0 表示平台级公共模板 */
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 0L;

    /** RAG_QA / CS_REPLY / REWRITE / HYDE / RERANK */
    @Column(name = "template_code", nullable = false, length = 64)
    private String templateCode;

    @Column(name = "template_name", nullable = false, length = 96)
    private String templateName;

    /** 模板正文，支持 {{question}} {{context}} 等占位符 */
    @Lob
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 可用变量列表（JSON 数组字符串） */
    @Column(name = "variables", columnDefinition = "JSON")
    private String variables;

    /** 1 启用 / 0 停用 */
    @Column(name = "enabled", nullable = false)
    private Integer enabled = 1;
}
