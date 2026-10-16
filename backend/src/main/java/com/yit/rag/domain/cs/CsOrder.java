package com.yit.rag.domain.cs;

import com.yit.rag.domain.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicInsert;

import java.math.BigDecimal;

/**
 * 订单（对应 {@code cs_order}）—— 智能客服工具 queryOrder 的数据源。
 *
 * <p>这个类解决什么问题：Tool Calling 要查的是真实业务数据，不是幻觉。
 * 用户在对话里说「我的订单 SO20261016001 到哪了」，
 * Agent 识别出订单号 → 调 queryOrder 工具 → 查这张表 → 把结构化结果回灌给模型，
 * 模型再组织成自然语言。这样客服回答里的订单状态一定是库里的真实值。
 */
@Getter
@Setter
@Entity
@DynamicInsert
@Table(name = "cs_order")
public class CsOrder extends CreatedEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** 订单号，如 SO20261016001（全局唯一） */
    @Column(name = "order_no", nullable = false, length = 32)
    private String orderNo;

    @Column(name = "customer_name", nullable = false, length = 64)
    private String customerName;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "product_name", nullable = false, length = 128)
    private String productName;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    /** PAID / SHIPPED / DELIVERED / REFUNDING / CLOSED */
    @Column(name = "order_status", nullable = false, length = 24)
    private String orderStatus;

    @Column(name = "logistics_no", length = 64)
    private String logisticsNo;

    /** 订单状态的中文描述，用于回灌给模型（模型更容易组织成人话） */
    public String statusText() {
        if (orderStatus == null) {
            return "未知";
        }
        return switch (orderStatus) {
            case "PAID" -> "已付款待发货";
            case "SHIPPED" -> "已发货";
            case "DELIVERED" -> "已签收";
            case "REFUNDING" -> "退款处理中";
            case "CLOSED" -> "已关闭";
            default -> orderStatus;
        };
    }
}
