package com.yit.rag.service.admin;

import com.yit.rag.api.dto.admin.AdminDtos;
import com.yit.rag.common.tenant.TenantContext;
import com.yit.rag.repository.ai.AiCallLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 成本统计服务 —— 让「模型花了多少钱」变成可看的报表。
 *
 * <p>这个类解决什么问题：多模型平台最容易失控的就是成本。
 * 没有人知道这个月 DeepSeek 花了多少、哪个模型最贵、哪家失败率最高，
 * 就没法做容量规划和供应商谈判。所以这里把 {@code ai_call_log} 聚合成三个维度：
 *
 * <ol>
 *   <li><b>按天</b>（byDay）：看趋势。「上周改了提示词之后 token 用量翻倍了」这类问题一眼可见。</li>
 *   <li><b>按模型</b>（byModel）：看结构。哪个模型吃掉了大部分预算，该不该换更便宜的。</li>
 *   <li><b>按提供方</b>（byProvider）：看稳定性 + Fallback 效果。
 *       失败次数高的提供方说明不可靠；调用量集中在 mock 说明真实 Key 根本没配。</li>
 * </ol>
 *
 * <p>两个实现要点：
 * <ul>
 *   <li><b>聚合下推到 SQL</b>：{@code group by date(created_at)} / {@code group by model}
 *       交给 MySQL 做。如果把明细全捞到内存里用 Stream 算，几十万条日志会直接把堆撑爆。</li>
 *   <li><b>BigDecimal 处理金额</b>：钱不能用 double，否则累加几千条之后会出现
 *       零点几分的误差，月度对账时非常难解释。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostReportService {

    private final AiCallLogRepository callLogRepository;

    /**
     * 生成成本报表。
     *
     * @param days 统计最近多少天（含今天），默认 7；上限 90，避免全表扫描
     */
    @Transactional(readOnly = true)
    public AdminDtos.CostReport report(Integer days) {
        Long tenantId = TenantContext.requireTenantId();
        int d = (days == null || days <= 0) ? 7 : Math.min(days, 90);
        LocalDateTime from = LocalDate.now().minusDays(d - 1L).atStartOfDay();

        // ---------- 总计 ----------
        long totalTokens = 0;
        BigDecimal totalCost = BigDecimal.ZERO;
        long totalCalls = 0;
        // 注意：这里刻意用 List<Object[]> 而不是裸 Object[]。
        // 详见 AiCallLogRepository.summary() 上的注释 —— 多列查询用裸数组返回时
        // 会被额外包装成 Object[]{ Object[]{...} }，导致下面的取值全部落空且不报错。
        List<Object[]> summaryRows = callLogRepository.summary(tenantId, from);
        if (summaryRows != null && !summaryRows.isEmpty() && summaryRows.get(0) != null) {
            Object[] summary = summaryRows.get(0);
            if (summary.length >= 3) {
                totalTokens = toLong(summary[0]);
                totalCost = toDecimal(summary[1]);
                totalCalls = toLong(summary[2]);
            } else {
                // 宁可打日志也不要静默返回 0：这个坑第一次就是靠「怎么都是 0」发现的
                log.warn("成本汇总返回的列数异常（预期 3 列，实际 {} 列），已按 0 处理: {}",
                        summary.length, java.util.Arrays.toString(summary));
            }
        } else {
            log.debug("成本汇总无数据: tenantId={} from={}", tenantId, from);
        }

        // ---------- 按天 ----------
        List<AdminDtos.DayCost> byDay = new ArrayList<>();
        for (Object[] row : callLogRepository.sumByDay(tenantId, from)) {
            byDay.add(new AdminDtos.DayCost(
                    row[0] == null ? null : String.valueOf(row[0]),
                    toLong(row[1]),
                    toDecimal(row[2])));
        }

        // ---------- 按模型 ----------
        List<AdminDtos.ModelCost> byModel = new ArrayList<>();
        for (Object[] row : callLogRepository.sumByModel(tenantId, from)) {
            byModel.add(new AdminDtos.ModelCost(
                    row[0] == null ? "unknown" : String.valueOf(row[0]),
                    toLong(row[1]),
                    toDecimal(row[2]),
                    toLong(row[3])));
        }

        // ---------- 按提供方（含失败数，反映 Fallback 与稳定性） ----------
        List<AdminDtos.ProviderStat> byProvider = new ArrayList<>();
        for (Object[] row : callLogRepository.sumByProvider(tenantId, from)) {
            long calls = toLong(row[1]);
            long fails = toLong(row[2]);
            byProvider.add(new AdminDtos.ProviderStat(
                    row[0] == null ? "unknown" : String.valueOf(row[0]), calls, fails));
        }

        log.debug("成本报表: tenantId={} days={} 总token={} 总成本={}元 调用={}次",
                tenantId, d, totalTokens, totalCost, totalCalls);

        return new AdminDtos.CostReport(totalTokens, totalCost, totalCalls, d, byDay, byModel, byProvider);
    }

    /** 数值转换：原生 SQL 返回的类型可能是 Long / BigInteger / BigDecimal */
    private static long toLong(Object o) {
        if (o == null) {
            return 0L;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 金额转换：统一成 6 位小数的 BigDecimal（与表字段 scale 一致） */
    private static BigDecimal toDecimal(Object o) {
        if (o == null) {
            return BigDecimal.ZERO;
        }
        if (o instanceof BigDecimal bd) {
            return bd.setScale(6, RoundingMode.HALF_UP);
        }
        if (o instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue()).setScale(6, RoundingMode.HALF_UP);
        }
        try {
            return new BigDecimal(String.valueOf(o)).setScale(6, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
