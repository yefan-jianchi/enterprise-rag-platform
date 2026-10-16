package com.yit.rag.domain.ai;

import com.yit.rag.domain.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicInsert;

import java.math.BigDecimal;

/**
 * 模型调用日志（对应 {@code ai_call_log}）—— 审计 + 成本核算。
 *
 * <p>这个类解决什么问题：多模型 + Fallback 之后，调用链会变得不透明
 * （这次到底走的哪家？为什么慢？花了多少钱？）。所以每次调用都落一条：
 * <ul>
 *   <li>{@code providerCode / model}：定位到具体模型</li>
 *   <li>{@code promptTokens / completionTokens / totalTokens}：用量</li>
 *   <li>{@code costYuan}：按配置单价折算的人民币成本，管理端成本报表直接 group by 出来</li>
 *   <li>{@code success / errorMsg}：失败的调用同样要记，否则「Fallback 到底触发了几次」无从统计</li>
 * </ul>
 *
 * <p>写日志同样不能拖垮主流程：统一走 {@code AuditService} 的容错写入。
 */
@Getter
@Setter
@Entity
@DynamicInsert
@Table(name = "ai_call_log")
public class AiCallLog extends CreatedEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "session_id")
    private Long sessionId;

    /** 链路追踪 ID，串联 网关 / 后端 / AI 服务 / 模型厂商 */
    @Column(name = "trace_id", length = 32)
    private String traceId;

    /** CHAT / EMBED / RERANK / REWRITE / TOOL */
    @Column(name = "call_type", nullable = false, length = 24)
    private String callType;

    @Column(name = "provider_code", nullable = false, length = 48)
    private String providerCode;

    @Column(name = "model", nullable = false, length = 64)
    private String model;

    @Column(name = "prompt_tokens", nullable = false)
    private Integer promptTokens = 0;

    @Column(name = "completion_tokens", nullable = false)
    private Integer completionTokens = 0;

    @Column(name = "total_tokens", nullable = false)
    private Integer totalTokens = 0;

    /** 按单价折算的人民币成本 */
    @Column(name = "cost_yuan", nullable = false, precision = 12, scale = 6)
    private BigDecimal costYuan = BigDecimal.ZERO;

    @Column(name = "latency_ms", nullable = false)
    private Integer latencyMs = 0;

    /** 1 成功 / 0 失败 */
    @Column(name = "success", nullable = false)
    private Integer success = 1;

    @Column(name = "error_msg", length = 500)
    private String errorMsg;

    /** 调用类型常量 */
    public static final class CallType {
        public static final String CHAT = "CHAT";
        public static final String EMBED = "EMBED";
        public static final String RERANK = "RERANK";
        public static final String REWRITE = "REWRITE";
        public static final String TOOL = "TOOL";

        private CallType() {
        }
    }
}
