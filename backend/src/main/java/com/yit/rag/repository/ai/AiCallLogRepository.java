package com.yit.rag.repository.ai;

import com.yit.rag.domain.ai.AiCallLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 模型调用日志仓储 —— 成本统计报表的数据源。
 *
 * <p>这个类解决什么问题：管理端要做「按天」「按模型」两个维度的成本报表。
 * 如果全捞到内存里用 Stream 算，数据量一大就 OOM，所以这里把聚合下推到 SQL：
 * {@code date(created_at)} 分组 + {@code sum()}，数据库干聚合的活最合适。
 *
 * <p>注意所有统计都带 tenant_id：成本是要跟客户对账的，串租户就等于算错钱。
 */
public interface AiCallLogRepository extends JpaRepository<AiCallLog, Long> {

    /**
     * 时间窗口内的汇总（总 token / 总成本 / 总调用次数）：返回一行三列。
     *
     * <p><b>这里踩过一个很隐蔽的坑，值得记下来：</b>
     * 最初写的是 JPQL 多列查询 + 返回类型 {@code Object[]}：
     * <pre>
     *   @Query("select coalesce(sum(l.totalTokens),0), ... from AiCallLog l where ...")
     *   Object[] summary(...);
     * </pre>
     * 结果接口永远返回 0，但库里明明有数据。原因是 Spring Data JPA 对
     * 「多列查询 + 数组返回类型」存在<b>额外包装</b>：真实拿到的是
     * {@code Object[]{ Object[]{322, 0.000628, 7} }} —— 外层长度是 1，
     * 于是调用方的 {@code summary.length >= 3} 守卫静默不成立，一路保持 0，
     * 既不报错也不抛异常，非常难查。
     *
     * <p>修复方式：改成<b>原生查询 + {@code List<Object[]>}</b>，
     * 与本类其它三个聚合方法（byDay / byModel / byProvider）保持完全一致。
     * 用 List 而不是裸数组，语义明确：一行结果 = 一个元素，
     * 不会再有「到底是行还是列」的歧义。
     */
    @Query(value = """
            SELECT COALESCE(SUM(total_tokens), 0) AS t,
                   COALESCE(SUM(cost_yuan), 0)    AS c,
                   COUNT(*)                       AS n
            FROM ai_call_log
            WHERE tenant_id = :tenantId AND created_at >= :from
            """, nativeQuery = true)
    List<Object[]> summary(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from);

    /** 按天分组：返回 [日期字符串, tokens, 成本] */
    @Query(value = """
            SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS d,
                   COALESCE(SUM(total_tokens), 0)      AS t,
                   COALESCE(SUM(cost_yuan), 0)         AS c
            FROM ai_call_log
            WHERE tenant_id = :tenantId AND created_at >= :from
            GROUP BY d
            ORDER BY d
            """, nativeQuery = true)
    List<Object[]> sumByDay(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from);

    /** 按模型分组：返回 [model, tokens, 成本, 调用次数] */
    @Query(value = """
            SELECT model,
                   COALESCE(SUM(total_tokens), 0) AS t,
                   COALESCE(SUM(cost_yuan), 0)    AS c,
                   COUNT(*)                       AS n
            FROM ai_call_log
            WHERE tenant_id = :tenantId AND created_at >= :from
            GROUP BY model
            ORDER BY t DESC
            """, nativeQuery = true)
    List<Object[]> sumByModel(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from);

    /** 按提供方分组：用于展示 Fallback 之后各家的实际承担量 */
    @Query(value = """
            SELECT provider_code,
                   COUNT(*)                                              AS n,
                   SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END)          AS fails
            FROM ai_call_log
            WHERE tenant_id = :tenantId AND created_at >= :from
            GROUP BY provider_code
            ORDER BY n DESC
            """, nativeQuery = true)
    List<Object[]> sumByProvider(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from);
}
