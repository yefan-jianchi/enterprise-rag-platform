package com.yit.rag.domain.cs;

import com.yit.rag.domain.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicInsert;

/**
 * 工单（对应 {@code cs_ticket}）—— 智能客服工具 createTicket 的落地表。
 *
 * <p>这个类解决什么问题：对话里解决不了的问题要能「沉淀成待办」。
 * 用户在客服会话里说「我要投诉，音箱配网一直失败」，
 * Agent 判断需要建单 → 调 createTicket 工具 → 生成 TK 开头的工单号返回给用户。
 * 这样「对话」和「工单系统」就打通了，而不是聊完什么都没留下。
 *
 * <p>{@code ticketNo} 由后端生成（TK + 日期 + 序号），不依赖数据库自增，
 * 便于对外沟通与跨系统对账。
 */
@Getter
@Setter
@Entity
@DynamicInsert
@Table(name = "cs_ticket")
public class CsTicket extends AuditedEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** 工单号，如 TK20261016001（全局唯一） */
    @Column(name = "ticket_no", nullable = false, length = 32)
    private String ticketNo;

    @Column(name = "user_id")
    private Long userId;

    /** 关联订单号，可为空 */
    @Column(name = "order_no", length = 32)
    private String orderNo;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Lob
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /** LOW / NORMAL / HIGH / URGENT */
    @Column(name = "priority", nullable = false, length = 16)
    private String priority = "NORMAL";

    /** OPEN / PROCESSING / RESOLVED / CLOSED */
    @Column(name = "status", nullable = false, length = 24)
    private String status = "OPEN";

    @Column(name = "assignee", length = 64)
    private String assignee;
}
