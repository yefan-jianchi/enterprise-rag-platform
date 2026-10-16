package com.yit.rag.api;

import com.yit.rag.api.dto.chat.ChatDtos;
import com.yit.rag.common.R;
import com.yit.rag.common.tenant.TenantContext;
import com.yit.rag.domain.cs.CsHandoff;
import com.yit.rag.domain.cs.CsOrder;
import com.yit.rag.domain.cs.CsTicket;
import com.yit.rag.repository.cs.CsHandoffRepository;
import com.yit.rag.repository.cs.CsOrderRepository;
import com.yit.rag.repository.cs.CsTicketRepository;
import com.yit.rag.service.chat.ChatSessionService;
import com.yit.rag.service.cs.CustomerServiceAgent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 智能客服接口。
 *
 * <p>这个类解决什么问题：对外提供「客服对话（SSE，走 Tool Calling）」+
 * 「订单/工单/转人工数据的查询」三类能力。
 *
 * <p>注意 {@code /api/cs/stream} 与 {@code /api/chat/stream} 的<b>事件协议完全一致</b>
 * （meta / delta / done / error），所以前端可以复用同一套 SSE 解析代码，
 * 只是请求打到不同路径、后端走不同的 Agent 逻辑。
 *
 * <p>{@code /api/cs/stream} 同样兼容「前端自己生成 sessionUuid」的用法：
 * 未知 UUID 会自动落库建一条 {@code scene='CS'} 的会话。
 */
@Slf4j
@RestController
@RequestMapping("/api/cs")
@RequiredArgsConstructor
@Tag(name = "智能客服", description = "Tool Calling 客服对话、订单、工单、转人工")
public class CustomerServiceController {

    private final CustomerServiceAgent agent;
    private final ChatSessionService sessionService;
    private final CsOrderRepository orderRepository;
    private final CsTicketRepository ticketRepository;
    private final CsHandoffRepository handoffRepository;

    @Qualifier("sseExecutor")
    private final Executor sseExecutor;

    // ==================================================================
    //  客服对话（SSE + Tool Calling）
    // ==================================================================

    /**
     * 智能客服流式对话。
     *
     * <p>Provider 为 mock（本机默认）时走规则意图识别，真实模型时走 Tool Calling 循环；
     * 两条路径执行的是同一批工具（queryOrder / createTicket / transferToHuman）。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('chat:ask')")
    @Operation(summary = "智能客服对话", description = "SSE + Tool Calling：meta/delta/done/error")
    public SseEmitter stream(@Valid @RequestBody ChatDtos.StreamRequest request) {
        SseEmitter emitter = new SseEmitter(CustomerServiceAgent.SSE_TIMEOUT_MS);
        emitter.onCompletion(() -> log.debug("[cs] SSE 流正常结束"));
        emitter.onTimeout(() -> {
            log.warn("[cs] SSE 流超时，主动关闭");
            emitter.complete();
        });
        emitter.onError(t -> log.info("[cs] SSE 流异常结束: {}", t.getMessage()));

        // ⚠ 关键：TenantContext 是 ThreadLocal，sseExecutor 的新线程拿不到，
        // 必须在当前请求线程里取出用户上下文，显式传给异步任务。
        TenantContext.CurrentUser currentUser = TenantContext.get();

        try {
            sseExecutor.execute(() -> ChatController.runWithTenant(currentUser, () -> agent.stream(emitter, request)));
        } catch (RejectedExecutionException e) {
            log.warn("SSE 线程池已满，拒绝客服请求: {}", e.getMessage());
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(java.util.Map.of("message", "当前并发对话过多，请稍后重试")));
            } catch (Exception ignored) {
                // 客户端可能已断开
            }
            emitter.complete();
        }
        return emitter;
    }

    // ==================================================================
    //  订单
    // ==================================================================

    /**
     * 订单列表（支持关键词模糊查询）。
     *
     * <p>{@code keyword} 可匹配订单号 / 客户名 / 手机号 / 商品名。
     * 不传时返回当前租户全部订单（种子数据里 demo 租户有 4 条）。
     */
    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('cs:ticket:view')")
    @Transactional(readOnly = true)
    @Operation(summary = "订单列表", description = "keyword 可选，匹配订单号/客户名/手机号/商品名")
    public R<List<ChatDtos.OrderItem>> orders(
            @RequestParam(value = "keyword", required = false) String keyword) {
        Long tenantId = TenantContext.requireTenantId();
        List<CsOrder> list = orderRepository.search(tenantId, keyword);
        log.debug("订单查询: tenantId={} keyword={} 命中={}", tenantId, keyword, list.size());
        return R.ok(list.stream().map(CustomerServiceController::toOrderItem).toList());
    }

    // ==================================================================
    //  工单
    // ==================================================================

    /** 工单列表（当前租户） */
    @GetMapping("/tickets")
    @PreAuthorize("hasAuthority('cs:ticket:view')")
    @Transactional(readOnly = true)
    @Operation(summary = "工单列表")
    public R<List<ChatDtos.TicketItem>> tickets() {
        Long tenantId = TenantContext.requireTenantId();
        return R.ok(ticketRepository.findByTenantIdOrderByIdDesc(tenantId)
                .stream().map(CustomerServiceController::toTicketItem).toList());
    }

    // ==================================================================
    //  转人工
    // ==================================================================

    /**
     * 转人工。
     *
     * <p>这是给前端「转人工」按钮用的显式接口（与 Agent 内部工具
     * transferToHuman 落的是同一张表、同一套幂等逻辑）。
     * 之所以两者都要有：按钮点击是确定性操作，不应该依赖模型判断；
     * 而对话里的「转人工」则由意图识别触发。
     */
    @PostMapping("/handoff")
    @PreAuthorize("hasAuthority('cs:handoff')")
    @Transactional
    @Operation(summary = "转人工", description = "为指定会话创建一条 WAITING 的转人工记录")
    public R<ChatDtos.HandoffItem> handoff(@Valid @RequestBody ChatDtos.HandoffRequest request) {
        Long tenantId = TenantContext.requireTenantId();
        // 会话必须存在（转人工一般是点了按钮，此时会话一定已经建好）
        var session = sessionService.require(request.sessionUuid());

        // 幂等：已经有 WAITING 记录就直接返回，避免用户连点产生多条待接单
        var existing = handoffRepository.findFirstByTenantIdAndSessionIdAndStatusOrderByIdDesc(
                tenantId, session.getId(), "WAITING");
        if (existing.isPresent()) {
            log.info("会话 {} 已有待接管的转人工记录，直接返回", request.sessionUuid());
            return R.ok(toHandoffItem(existing.get()));
        }

        CsHandoff handoff = new CsHandoff();
        handoff.setTenantId(tenantId);
        handoff.setSessionId(session.getId());
        handoff.setReason(request.reason() == null || request.reason().isBlank()
                ? "用户主动要求转人工" : request.reason().trim());
        handoff.setStatus("WAITING");
        handoff = handoffRepository.save(handoff);

        log.info("转人工记录已创建: id={} sessionUuid={} reason={}",
                handoff.getId(), request.sessionUuid(), handoff.getReason());
        return R.ok(toHandoffItem(handoff));
    }

    /** 转人工记录列表（坐席工作台用） */
    @GetMapping("/handoffs")
    @PreAuthorize("hasAuthority('cs:ticket:view')")
    @Transactional(readOnly = true)
    @Operation(summary = "转人工记录列表")
    public R<List<ChatDtos.HandoffItem>> handoffs() {
        Long tenantId = TenantContext.requireTenantId();
        return R.ok(handoffRepository.findByTenantIdOrderByIdDesc(tenantId)
                .stream().map(CustomerServiceController::toHandoffItem).toList());
    }

    // ==================================================================
    //  概览（演示用）
    // ==================================================================

    /** 客服数据概览：订单 / 工单 / 转人工数量 */
    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('cs:ticket:view')")
    @Operation(summary = "客服数据概览")
    public R<java.util.Map<String, Object>> overview() {
        Long tenantId = TenantContext.requireTenantId();
        return R.ok(agent.csOverview(tenantId));
    }

    // ==================================================================
    //  转换
    // ==================================================================

    private static ChatDtos.OrderItem toOrderItem(CsOrder o) {
        return new ChatDtos.OrderItem(
                o.getId(), o.getOrderNo(), o.getCustomerName(), o.getPhone(),
                o.getProductName(), o.getAmount(), o.getOrderStatus(),
                o.getLogisticsNo(), o.statusText(), o.getCreatedAt());
    }

    private static ChatDtos.TicketItem toTicketItem(CsTicket t) {
        return new ChatDtos.TicketItem(
                t.getId(), t.getTicketNo(), t.getOrderNo(), t.getTitle(), t.getContent(),
                t.getPriority(), t.getStatus(), t.getAssignee(), t.getCreatedAt());
    }

    private static ChatDtos.HandoffItem toHandoffItem(CsHandoff h) {
        return new ChatDtos.HandoffItem(
                h.getId(), h.getSessionId(), h.getReason(), h.getStatus(),
                h.getAgentName(), h.getCreatedAt());
    }
}
