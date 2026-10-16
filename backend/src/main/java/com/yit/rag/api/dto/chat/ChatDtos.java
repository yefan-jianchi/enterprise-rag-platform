package com.yit.rag.api.dto.chat;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.validation.constraints.NotBlank;

/**
 * 会话相关的接口出入参。
 *
 * <p>严格对齐前端契约，字段名不要改。
 */
public final class ChatDtos {

    private ChatDtos() {
    }

    /**
     * 流式问答请求体（{@code /api/chat/stream} 与 {@code /api/cs/stream} 共用）。
     *
     * <p>{@code sessionUuid} 允许是前端 {@code crypto.randomUUID()} 生成的、数据库里还不存在的值 ——
     * 后端会自动落库建一条会话（见 {@code ChatSessionService.requireOrCreate}）。
     * 这样前端就不需要先调「创建会话」接口，少一次往返，客服页尤其方便。
     */
    public record StreamRequest(
            String sessionUuid,
            @NotBlank(message = "问题不能为空") String question,
            Long kbId,
            String providerCode,
            String strategy
    ) {
    }

    /** 建会话请求 */
    public record CreateSessionRequest(
            Long kbId,
            String title,
            String providerCode,
            /** CHAT / CS，缺省 CHAT */
            String scene
    ) {
    }

    /** 会话列表项 / 详情 */
    public record SessionItem(
            Long id,
            String sessionUuid,
            String title,
            Long kbId,
            String providerCode,
            String scene,
            Integer messageCount,
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")            java.time.LocalDateTime createdAt,
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")            java.time.LocalDateTime updatedAt
    ) {
    }

    /** 消息项 */
    public record MessageItem(
            Long id,
            String role,
            String content,
            /** 引用溯源原始 JSON（前端直接解析） */
            String citations,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            Integer latencyMs,
            String traceId,
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")            java.time.LocalDateTime createdAt
    ) {
    }

    /** 转人工请求 */
    public record HandoffRequest(
            @NotBlank(message = "sessionUuid 不能为空") String sessionUuid,
            String reason
    ) {
    }

    /** 订单项（智能客服订单查询结果） */
    public record OrderItem(
            Long id,
            String orderNo,
            String customerName,
            String phone,
            String productName,
            java.math.BigDecimal amount,
            String orderStatus,
            String logisticsNo,
            /** 状态中文描述，前端直接展示 */
            String statusText,
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")            java.time.LocalDateTime createdAt
    ) {
    }

    /** 工单项 */
    public record TicketItem(
            Long id,
            String ticketNo,
            String orderNo,
            String title,
            String content,
            String priority,
            String status,
            String assignee,
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")            java.time.LocalDateTime createdAt
    ) {
    }

    /** 转人工记录项 */
    public record HandoffItem(
            Long id,
            Long sessionId,
            String reason,
            String status,
            String agentName,
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")            java.time.LocalDateTime createdAt
    ) {
    }
}
