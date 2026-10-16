package com.yit.rag.service.cs;

import com.yit.rag.domain.cs.CsOrder;
import com.yit.rag.domain.cs.CsTicket;
import com.yit.rag.repository.cs.CsHandoffRepository;
import com.yit.rag.repository.cs.CsOrderRepository;
import com.yit.rag.repository.cs.CsTicketRepository;
import com.yit.rag.domain.cs.CsHandoff;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能客服工具集 —— Tool Calling 真正「干活」的地方。
 *
 * <p>这个类解决什么问题：大模型只会生成文字，它查不到订单、也建不了工单。
 * 「工具（Tool / Function Calling）」就是把模型和真实业务系统连起来的那座桥：
 * 模型决定「要调哪个工具、参数是什么」，后端负责真正执行，再把结果回灌给模型。
 *
 * <p>本项目实现 3 个工具，覆盖电商客服最高频的三类诉求：
 * <table border="1">
 *   <tr><th>工具</th><th>解决什么</th><th>落地到哪张表</th></tr>
 *   <tr><td>queryOrder</td><td>「我的订单到哪了？」</td><td>cs_order（只读）</td></tr>
 *   <tr><td>createTicket</td><td>「我要投诉 / 报修」</td><td>cs_ticket（写入）</td></tr>
 *   <tr><td>transferToHuman</td><td>「给我转人工」</td><td>cs_handoff（写入）</td></tr>
 * </table>
 *
 * <p><b>安全边界</b>（这点很重要，面试常被追问）：
 * <ul>
 *   <li>每个工具的第一个参数都是 {@code tenantId}，由后端从 JWT 取，<b>绝不来自模型</b>。
 *       模型输出的参数是不可信输入，如果让它决定租户，就是提示词注入导致的越权。</li>
 *   <li>所有查询都带 tenant_id 条件，模型说「查订单 XX」也只能查到本租户的订单。</li>
 *   <li>工具执行用独立事务（REQUIRES_NEW）：写工单/转人工要立刻提交，
 *       不能等整条 SSE 流结束（否则用户中途关页面，工单就丢了）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerServiceTools {

    /** 工具名常量：模型输出的 JSON 里用的就是这些名字 */
    public static final String TOOL_QUERY_ORDER = "queryOrder";
    public static final String TOOL_CREATE_TICKET = "createTicket";
    public static final String TOOL_TRANSFER_TO_HUMAN = "transferToHuman";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final CsOrderRepository orderRepository;
    private final CsTicketRepository ticketRepository;
    private final CsHandoffRepository handoffRepository;

    /**
     * 一次工具执行的结果。
     *
     * @param toolName 工具名
     * @param success  是否成功
     * @param payload  结构化结果（会序列化后回灌给模型 / 返回给前端）
     * @param summary  给模型看的自然语言摘要（比裸 JSON 更容易被模型正确复述）
     */
    public record ToolResult(String toolName, boolean success, Object payload, String summary) {
    }

    // ==================================================================
    //  工具 1：查询订单
    // ==================================================================

    /**
     * 查询订单 —— 支持订单号或手机号。
     *
     * <p>两种入参是有意设计的：用户经常记不住订单号，但一定记得自己手机号。
     * 电话查询返回该手机号下的全部订单，让模型自己挑。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ToolResult queryOrder(Long tenantId, String orderNo, String phone) {
        log.info("[工具] queryOrder 被调用: tenantId={} orderNo={} phone={}", tenantId, orderNo, mask(phone));

        if ((orderNo == null || orderNo.isBlank()) && (phone == null || phone.isBlank())) {
            return new ToolResult(TOOL_QUERY_ORDER, false, Map.of(),
                    "缺少查询条件：需要提供订单号（形如 SO20261016001）或下单手机号。");
        }

        // 优先按订单号精确查（命中率最高、结果最准）
        if (orderNo != null && !orderNo.isBlank()) {
            var found = orderRepository.findByTenantIdAndOrderNo(tenantId, orderNo.trim().toUpperCase());
            if (found.isEmpty()) {
                return new ToolResult(TOOL_QUERY_ORDER, false, Map.of("orderNo", orderNo),
                        "未查询到订单号为 " + orderNo + " 的订单。请确认订单号是否正确（格式为 SO + 数字）。");
            }
            CsOrder order = found.get();
            return new ToolResult(TOOL_QUERY_ORDER, true, toMap(order), describe(order));
        }

        // 再按手机号查
        List<CsOrder> orders = orderRepository.findByTenantIdAndPhoneOrderByIdDesc(tenantId, phone.trim());
        if (orders.isEmpty()) {
            return new ToolResult(TOOL_QUERY_ORDER, false, Map.of("phone", phone),
                    "未查询到手机号 " + mask(phone) + " 名下的订单。");
        }
        List<Map<String, Object>> list = orders.stream().map(this::toMap).toList();
        StringBuilder sb = new StringBuilder("手机号 " + mask(phone) + " 名下共 " + orders.size() + " 笔订单：");
        for (CsOrder o : orders) {
            sb.append("\n- ").append(describe(o));
        }
        return new ToolResult(TOOL_QUERY_ORDER, true, list, sb.toString());
    }

    // ==================================================================
    //  工具 2：创建工单
    // ==================================================================

    /**
     * 创建工单 —— 把「对话里解决不了的问题」沉淀成待办。
     *
     * <p>工单号生成规则：{@code TK + yyyyMMdd + 3 位序号}。
     * 序号由「查当天最大号 + 1」得到。这里没有用数据库自增是因为：
     * <ul>
     *   <li>工单号要对外沟通，需要可读、可按日期检索</li>
     *   <li>自增号会暴露业务量（竞争对手看一眼就知道你一天多少单）</li>
     * </ul>
     *
     * <p>并发说明：极端并发下「查最大号 + 1」可能撞号，靠
     * {@code uk_ticket_no} 唯一索引兜底（插入失败就是撞号，可以重试）。
     * 高并发场景应该改用 Redis INCR 或号段模式，这里保持实现简单。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ToolResult createTicket(Long tenantId, Long userId, String orderNo,
                                   String title, String content, String priority) {
        log.info("[工具] createTicket 被调用: tenantId={} orderNo={} title={} priority={}",
                tenantId, orderNo, title, priority);

        if (title == null || title.isBlank()) {
            return new ToolResult(TOOL_CREATE_TICKET, false, Map.of(),
                    "创建工单失败：必须提供问题标题。");
        }

        String ticketNo = nextTicketNo(tenantId);
        CsTicket ticket = new CsTicket();
        ticket.setTenantId(tenantId);
        ticket.setTicketNo(ticketNo);
        ticket.setUserId(userId);
        ticket.setOrderNo(orderNo == null || orderNo.isBlank() ? null : orderNo.trim().toUpperCase());
        ticket.setTitle(truncate(title.trim(), 200));
        ticket.setContent(content == null ? title.trim() : truncate(content.trim(), 2000));
        ticket.setPriority(normalizePriority(priority));
        ticket.setStatus("OPEN");
        ticketRepository.save(ticket);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticketNo", ticketNo);
        payload.put("title", ticket.getTitle());
        payload.put("priority", ticket.getPriority());
        payload.put("status", ticket.getStatus());
        payload.put("orderNo", ticket.getOrderNo());

        String summary = "工单创建成功。工单号 " + ticketNo + "，标题「" + ticket.getTitle()
                + "」，优先级 " + ticket.getPriority() + "，当前状态 OPEN（待处理）。"
                + "请把这个工单号告诉用户。";
        log.info("[工具] 工单已创建: ticketNo={} tenantId={}", ticketNo, tenantId);
        return new ToolResult(TOOL_CREATE_TICKET, true, payload, summary);
    }

    // ==================================================================
    //  工具 3：转人工
    // ==================================================================

    /**
     * 转人工 —— AI 客服的「认怂」能力。
     *
     * <p>幂等性处理：如果该会话已经有一条 WAITING 的转人工记录，
     * 直接返回原来那条，不再重复插入。原因是用户可能连点两次，
     * 或者模型在同一轮里重复调用工具；重复插入会让坐席看到多条一模一样的待接单，
     * 是纯粹的噪音。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ToolResult transferToHuman(Long tenantId, Long sessionId, String reason) {
        log.info("[工具] transferToHuman 被调用: tenantId={} sessionId={} reason={}",
                tenantId, sessionId, reason);

        var existing = handoffRepository.findFirstByTenantIdAndSessionIdAndStatusOrderByIdDesc(
                tenantId, sessionId, "WAITING");
        if (existing.isPresent()) {
            CsHandoff h = existing.get();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("handoffId", h.getId());
            payload.put("status", h.getStatus());
            payload.put("reason", h.getReason());
            payload.put("duplicated", true);
            return new ToolResult(TOOL_TRANSFER_TO_HUMAN, true, payload,
                    "该会话此前已提交转人工申请（工单状态 WAITING），无需重复提交，请告知用户坐席会尽快接入。");
        }

        CsHandoff handoff = new CsHandoff();
        handoff.setTenantId(tenantId);
        handoff.setSessionId(sessionId);
        handoff.setReason(truncate(reason == null || reason.isBlank() ? "用户主动要求转人工" : reason.trim(), 255));
        handoff.setStatus("WAITING");
        handoffRepository.save(handoff);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("handoffId", handoff.getId());
        payload.put("status", handoff.getStatus());
        payload.put("reason", handoff.getReason());

        log.info("[工具] 转人工记录已创建: id={} tenantId={} sessionId={}", handoff.getId(), tenantId, sessionId);
        return new ToolResult(TOOL_TRANSFER_TO_HUMAN, true, payload,
                "已转人工。原因：" + handoff.getReason() + "。当前状态 WAITING（等待坐席接入），"
                        + "请礼貌地告知用户「已为你转接人工客服，请稍候」。");
    }

    // ==================================================================
    //  工具描述（给模型看的「说明书」）
    // ==================================================================

    /**
     * 返回工具清单的 JSON 描述，拼进系统提示词让模型知道有哪些工具可用。
     *
     * <p>为什么不依赖 Spring AI 的 {@code @Tool} 注解：本项目刻意不引入 spring-ai，
     * 一是版本迭代太快、抽象层厚（出问题难定位），二是「让模型输出 JSON 工具调用」
     * 这套协议自己实现只要几十行，而且对模型能力的要求更低
     * （DeepSeek / 通义 / 本地模型都能按提示词输出 JSON），可讲性也更强。
     */
    public String toolsSpec() {
        return """
                [
                  {"name":"queryOrder",
                   "description":"查询订单详情。用户询问订单状态、物流、金额时使用。",
                   "parameters":{"orderNo":"订单号，形如 SO20261016001（可选）",
                                 "phone":"下单手机号（可选，与 orderNo 二选一）"}},
                  {"name":"createTicket",
                   "description":"创建客服工单。用户投诉、报修、申请售后，或问题无法立即解决时使用。",
                   "parameters":{"orderNo":"关联订单号（可选）",
                                 "title":"问题标题（必填，20 字内）",
                                 "content":"问题详细描述（可选）",
                                 "priority":"LOW/NORMAL/HIGH/URGENT（可选，默认 NORMAL）"}},
                  {"name":"transferToHuman",
                   "description":"转人工客服。用户明确要求人工、或连续两轮无法解决时使用。",
                   "parameters":{"reason":"转人工原因（可选）"}}
                ]""";
    }

    // ==================================================================
    //  内部工具
    // ==================================================================

    /** 把订单转成 Map（回灌给模型 / 返回给前端） */
    private Map<String, Object> toMap(CsOrder o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderNo", o.getOrderNo());
        m.put("customerName", o.getCustomerName());
        m.put("productName", o.getProductName());
        m.put("amount", o.getAmount());
        m.put("orderStatus", o.getOrderStatus());
        m.put("statusText", o.statusText());
        m.put("logisticsNo", o.getLogisticsNo());
        return m;
    }

    /** 订单的自然语言描述（模型照着复述就不会错） */
    private String describe(CsOrder o) {
        StringBuilder sb = new StringBuilder();
        sb.append("订单号 ").append(o.getOrderNo())
                .append("，客户 ").append(o.getCustomerName())
                .append("，商品「").append(o.getProductName()).append("」")
                .append("，金额 ").append(o.getAmount()).append(" 元")
                .append("，状态 ").append(o.getOrderStatus()).append("（").append(o.statusText()).append("）");
        if (o.getLogisticsNo() != null && !o.getLogisticsNo().isBlank()) {
            sb.append("，物流单号 ").append(o.getLogisticsNo());
        } else {
            sb.append("，暂无物流单号");
        }
        sb.append("。");
        return sb.toString();
    }

    /** 生成当天的下一个工单号 */
    private String nextTicketNo(Long tenantId) {
        String prefix = "TK" + LocalDate.now().format(DATE_FMT);
        return ticketRepository
                .findFirstByTenantIdAndTicketNoStartingWithOrderByTicketNoDesc(tenantId, prefix)
                .map(last -> {
                    String tail = last.getTicketNo().substring(prefix.length());
                    try {
                        int n = Integer.parseInt(tail) + 1;
                        return prefix + String.format("%03d", n);
                    } catch (NumberFormatException e) {
                        return prefix + "001";
                    }
                })
                .orElse(prefix + "001");
    }

    /** 优先级规范化：模型可能给出非法值（如 "high"/"紧急"），这里兜底 */
    private String normalizePriority(String p) {
        if (p == null || p.isBlank()) {
            return "NORMAL";
        }
        return switch (p.trim().toUpperCase()) {
            case "LOW" -> "LOW";
            case "HIGH" -> "HIGH";
            case "URGENT" -> "URGENT";
            default -> "NORMAL";
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 手机号脱敏（日志里不要留完整手机号，这是合规要求） */
    private static String mask(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
