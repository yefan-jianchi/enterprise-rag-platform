package com.yit.rag.domain.cs;

import com.yit.rag.domain.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicInsert;

import java.time.LocalDateTime;

/**
 * 转人工记录（对应 {@code cs_handoff}）—— 智能客服工具 transferToHuman 的落地表。
 *
 * <p>这个类解决什么问题：AI 客服要有「认怂」的能力。当用户明确要求人工、
 * 或者问题超出知识库范围时，必须能把会话交出去，并留下记录让坐席接手。
 * 这条记录同时是运营指标：转人工率过高，说明知识库覆盖不足或模型不行。
 *
 * <p>{@code status} 走 WAITING → ACCEPTED → CLOSED，
 * 坐席接单时写 {@code agentName} 与 {@code acceptedAt}。
 */
@Getter
@Setter
@Entity
@DynamicInsert
@Table(name = "cs_handoff")
public class CsHandoff extends CreatedEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** 会话主键（chat_session.id，不是 uuid） */
    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** 转人工原因，如「用户主动要求」「模型置信度低」 */
    @Column(name = "reason", length = 255)
    private String reason;

    /** WAITING / ACCEPTED / CLOSED */
    @Column(name = "status", nullable = false, length = 24)
    private String status = "WAITING";

    @Column(name = "agent_name", length = 64)
    private String agentName;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;
}
