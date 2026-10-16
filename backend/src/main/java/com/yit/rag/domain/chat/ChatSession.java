package com.yit.rag.domain.chat;

import com.yit.rag.domain.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicInsert;

/**
 * 会话（对应 {@code chat_session}）。
 *
 * <p>这个类解决什么问题：把多轮问答归到一个会话里，同时承载三个业务语义：
 * <ul>
 *   <li>{@code kbId}：本会话绑定的知识库（为空表示纯模型闲聊，不检索）</li>
 *   <li>{@code providerCode}：本会话用哪个模型提供方，方便对比不同模型效果</li>
 *   <li>{@code scene}：CHAT 通用问答 / CS 智能客服 —— 同一个会话表支撑两条业务线</li>
 * </ul>
 *
 * <p>对外暴露 {@code sessionUuid} 而不是自增 id：UUID 不可枚举，
 * 避免攻击者靠递增 ID 遍历别人的会话。
 */
@Getter
@Setter
@Entity
@DynamicInsert
@Table(name = "chat_session")
public class ChatSession extends AuditedEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 对外暴露的会话标识（UUID，唯一） */
    @Column(name = "session_uuid", nullable = false, length = 36)
    private String sessionUuid;

    @Column(name = "title", length = 128)
    private String title;

    /** 绑定的知识库，NULL 表示纯模型对话 */
    @Column(name = "kb_id")
    private Long kbId;

    @Column(name = "provider_code", nullable = false, length = 48)
    private String providerCode = "deepseek";

    /** CHAT 通用问答 / CS 智能客服 */
    @Column(name = "scene", nullable = false, length = 24)
    private String scene = "CHAT";

    @Column(name = "message_count", nullable = false)
    private Integer messageCount = 0;
}
