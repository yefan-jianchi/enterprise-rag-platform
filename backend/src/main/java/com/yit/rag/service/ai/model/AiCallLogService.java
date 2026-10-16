package com.yit.rag.service.ai.model;

import com.yit.rag.config.AppProperties;
import com.yit.rag.domain.ai.AiCallLog;
import com.yit.rag.repository.ai.AiCallLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 模型调用日志服务 —— 审计与成本核算的落点。
 *
 * <p>这个类解决什么问题：多模型 + Fallback 之后，同一次用户提问可能先试 DeepSeek、
 * 失败后落到 Mock。没有日志的话，「这次到底走的哪家、为什么慢、花了多少钱」
 * 完全不可观测。所以每次调用（<b>包括失败的</b>）都要落一条 ai_call_log：
 * <ul>
 *   <li>成功：记录 tokens 与按单价折算的成本</li>
 *   <li>失败：记录 success=0 与错误原因 —— Fallback 触发率就是靠这个统计出来的</li>
 * </ul>
 *
 * <p>两个关键工程决策：
 * <ol>
 *   <li><b>独立事务（REQUIRES_NEW）</b>：日志必须能独立提交。
 *       如果和业务事务绑在一起，业务一失败日志就跟着回滚 ——
 *       而「失败的那次调用」恰恰是最需要被记录下来的。</li>
 *   <li><b>写日志失败不影响业务</b>：整个方法体包在 try-catch 里，
 *       只打 WARN。审计很重要，但不能因为审计表写不进去就让用户的问题答不出来。</li>
 * </ol>
 *
 * <p>成本折算公式：{@code cost = promptTokens/1000 * prompt单价 + completionTokens/1000 * completion单价}，
 * 单价单位是「元 / 千 token」，来自 {@code app.model-price.<provider>}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCallLogService {

    private final AiCallLogRepository repository;
    private final AppProperties props;

    /**
     * 记录一次成功的模型调用。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(Long tenantId, Long userId, Long sessionId, String traceId,
                              String callType, String providerCode, String model,
                              int promptTokens, int completionTokens, long latencyMs) {
        try {
            AiCallLog entity = new AiCallLog();
            entity.setTenantId(tenantId == null ? 0L : tenantId);
            entity.setUserId(userId);
            entity.setSessionId(sessionId);
            entity.setTraceId(truncate(traceId, 32));
            entity.setCallType(callType);
            entity.setProviderCode(providerCode);
            entity.setModel(model);
            entity.setPromptTokens(promptTokens);
            entity.setCompletionTokens(completionTokens);
            entity.setTotalTokens(promptTokens + completionTokens);
            entity.setCostYuan(calcCost(providerCode, promptTokens, completionTokens));
            entity.setLatencyMs((int) Math.min(latencyMs, Integer.MAX_VALUE));
            entity.setSuccess(1);
            repository.save(entity);
            log.debug("模型调用已记账: provider={} model={} prompt={} completion={} cost={}元 latency={}ms",
                    providerCode, model, promptTokens, completionTokens, entity.getCostYuan(), latencyMs);
        } catch (Exception e) {
            log.warn("写入模型调用日志失败（不影响业务）: {}", e.getMessage());
        }
    }

    /**
     * 记录一次失败的模型调用 —— Fallback 的证据链。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long tenantId, Long userId, Long sessionId, String traceId,
                              String callType, String providerCode, String model,
                              long latencyMs, String errorMsg) {
        try {
            AiCallLog entity = new AiCallLog();
            entity.setTenantId(tenantId == null ? 0L : tenantId);
            entity.setUserId(userId);
            entity.setSessionId(sessionId);
            entity.setTraceId(truncate(traceId, 32));
            entity.setCallType(callType);
            entity.setProviderCode(providerCode);
            entity.setModel(model);
            entity.setPromptTokens(0);
            entity.setCompletionTokens(0);
            entity.setTotalTokens(0);
            entity.setCostYuan(BigDecimal.ZERO);   // 失败的调用不计费
            entity.setLatencyMs((int) Math.min(latencyMs, Integer.MAX_VALUE));
            entity.setSuccess(0);
            entity.setErrorMsg(truncate(errorMsg, 500));
            repository.save(entity);
            log.warn("模型调用失败已记账: provider={} model={} latency={}ms error={}",
                    providerCode, model, latencyMs, errorMsg);
        } catch (Exception e) {
            log.warn("写入失败调用日志时又出错（忽略）: {}", e.getMessage());
        }
    }

    /**
     * 按单价折算成本（元）。
     *
     * <p>用 BigDecimal 而不是 double：钱的计算绝不能有浮点误差，
     * 否则做月度对账时会差几分钱，非常难解释。
     */
    public BigDecimal calcCost(String providerCode, int promptTokens, int completionTokens) {
        AppProperties.Price price = props.getModelPrice().of(providerCode);
        BigDecimal thousand = BigDecimal.valueOf(1000);
        BigDecimal p = BigDecimal.valueOf(price.getPrompt())
                .multiply(BigDecimal.valueOf(promptTokens))
                .divide(thousand, 6, RoundingMode.HALF_UP);
        BigDecimal c = BigDecimal.valueOf(price.getCompletion())
                .multiply(BigDecimal.valueOf(completionTokens))
                .divide(thousand, 6, RoundingMode.HALF_UP);
        return p.add(c).setScale(6, RoundingMode.HALF_UP);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
