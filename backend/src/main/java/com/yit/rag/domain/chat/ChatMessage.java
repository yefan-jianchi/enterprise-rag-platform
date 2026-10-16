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
 * 消息（对应 {@code chat_message}）。
 *
 * <p>这个类解决什么问题：一次问答落两条记录（user + assistant），
 * 并且 assistant 那条要带齐「可复盘」的信息：
 * <ul>
 *   <li>{@code citations}：JSON 数组，保存引用溯源（文档名/页码/片段/分数）</li>
 *   <li>{@code promptTokens / completionTokens / latencyMs}：性能与成本</li>
 *   <li>{@code traceId}：一条链路串起「后端 → AI 服务 → 模型厂商」，
 *       出问题可以直接拿 traceId 去 AI 服务日志里捞</li>
 * </ul>
 *
 * <p>{@code citations} 在库里是 JSON 类型，这里映射成 String：
 * Hibernate 6 对 MySQL JSON 的写入需要额外类型，映射成字符串最省事，
 * 上层用 Jackson 做序列化/反序列化，边界清晰。
 */
@Getter
@Setter
@Entity
@DynamicInsert
@Table(name = "chat_message")
public class ChatMessage extends CreatedEntity {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_TOOL = "tool";

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** system / user / assistant / tool */
    @Column(name = "role", nullable = false, length = 16)
    private String role;

    /** 消息正文（MEDIUMTEXT） */
    @Lob
    @Column(name = "content", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    /** 引用溯源 JSON： [{docId,docName,pageNo,chunkIndex,score,snippet}] */
    @Column(name = "citations", columnDefinition = "JSON")
    private String citations;

    @Column(name = "model", length = 64)
    private String model;

    @Column(name = "prompt_tokens", nullable = false)
    private Integer promptTokens = 0;

    @Column(name = "completion_tokens", nullable = false)
    private Integer completionTokens = 0;

    @Column(name = "latency_ms", nullable = false)
    private Integer latencyMs = 0;

    /** 链路追踪 ID（32 位 hex） */
    @Column(name = "trace_id", length = 32)
    private String traceId;
}
