package com.yit.rag.api.dto.admin;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.List;

/**
 * 管理端接口的出入参。
 *
 * <p>契约严格对齐前端：{@code /api/admin/audit} 返回 {@code {total, records}}，
 * {@code /api/admin/cost} 返回 {@code {totalTokens,totalCost,byDay,byModel}}。
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    /**
     * 模型提供方列表项。
     *
     * <p><b>安全要求</b>：绝对不能把 API Key 原文返回给前端。
     * 这里只给 {@code apiKeyMasked}（如 {@code sk-1****abcd}）和
     * {@code apiKeyConfigured}（配没配）两个信息 ——
     * 管理员需要知道「配了没有」，但永远不需要看到完整 Key。
     */
    public record ModelItem(
            Long id,
            String providerCode,
            String providerName,
            String baseUrl,
            String chatModel,
            String embedModel,
            Integer embedDim,
            Integer priority,
            Boolean enabled,
            /** 是否已配置 API Key（环境变量或数据库） */
            Boolean apiKeyConfigured,
            /** 脱敏后的 Key，未配置为空串 */
            String apiKeyMasked,
            /** Key 来源：env / db / none —— 排障时非常有用 */
            String apiKeySource,
            /** 当前是否真的可被调用（enabled && Key 可用） */
            Boolean callable,
            String extraJson,
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")            java.time.LocalDateTime createdAt
    ) {
    }

    /**
     * 更新模型配置的请求体。
     *
     * <p>字段全部可选（null = 不改）。特别说明 {@code apiKey}：
     * <ul>
     *   <li>{@code null} 或字段缺失 → 不改动现有 Key</li>
     *   <li>非空字符串 → 用 AES 加密后覆盖</li>
     *   <li>空字符串 {@code ""} → 清空 Key（用于停用某家模型）</li>
     * </ul>
     * 这个约定让前端可以只提交要改的字段。
     */
    public record UpdateModelRequest(
            Boolean enabled,
            Integer priority,
            String chatModel,
            String apiKey
    ) {
    }

    /** Prompt 模板项 */
    public record PromptItem(
            Long id,
            Long tenantId,
            String templateCode,
            String templateName,
            String content,
            String variables,
            Boolean enabled,
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")            java.time.LocalDateTime createdAt
    ) {
    }

    /** 更新 Prompt 请求（null = 不改） */
    public record UpdatePromptRequest(
            String content,
            Boolean enabled
    ) {
    }

    /**
     * 成本报表。
     *
     * @param totalTokens 时间窗口内总 token
     * @param totalCost   总成本（元）
     * @param totalCalls  总调用次数
     * @param byDay       按天：{date, tokens, cost}
     * @param byModel     按模型：{model, tokens, cost, calls}
     * @param byProvider  按提供方：{provider, calls, fails} —— 可以看出 Fallback 之后各家的实际承担量
     */
    public record CostReport(
            long totalTokens,
            BigDecimal totalCost,
            long totalCalls,
            int days,
            List<DayCost> byDay,
            List<ModelCost> byModel,
            List<ProviderStat> byProvider
    ) {
    }

    /** 某一天的成本 */
    public record DayCost(String date, long tokens, BigDecimal cost) {
    }

    /** 某个模型的成本与调用量 */
    public record ModelCost(String model, long tokens, BigDecimal cost, long calls) {
    }

    /** 某个提供方的调用与失败次数（成功率可以看出服务稳定性） */
    public record ProviderStat(String provider, long calls, long fails) {
    }
}
