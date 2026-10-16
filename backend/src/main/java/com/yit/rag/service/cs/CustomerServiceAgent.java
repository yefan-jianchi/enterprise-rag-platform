package com.yit.rag.service.cs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yit.rag.api.dto.chat.ChatDtos;
import com.yit.rag.common.exception.BizException;
import com.yit.rag.common.tenant.TenantContext;
import com.yit.rag.domain.chat.ChatSession;
import com.yit.rag.domain.kb.KnowledgeBase;
import com.yit.rag.repository.cs.CsHandoffRepository;
import com.yit.rag.repository.cs.CsOrderRepository;
import com.yit.rag.repository.cs.CsTicketRepository;
import com.yit.rag.service.ai.AiServiceClient;
import com.yit.rag.service.ai.dto.AiDtos;
import com.yit.rag.service.ai.model.AiCallLogService;
import com.yit.rag.service.ai.model.ChatModel;
import com.yit.rag.service.ai.model.ModelRouter;
import com.yit.rag.service.chat.ChatModelStreamService;
import com.yit.rag.service.chat.ChatSessionService;
import com.yit.rag.service.chat.SseWriter;
import com.yit.rag.service.kb.KnowledgeBaseService;
import com.yit.rag.service.system.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能客服 Agent —— Tool Calling 的编排者。
 *
 * <p>这个类解决什么问题：通用 RAG 问答只能「照着知识库回答」，
 * 但客服场景需要查真实订单、建工单、转人工 —— 这些必须动手做事，光靠检索做不到。
 * 这个类实现完整的「模型决定 → 后端执行 → 结果回灌 → 模型作答」循环。
 *
 * <h3>两种执行模式（这是本模块最关键的设计）</h3>
 *
 * <p><b>模式 A：真实模型（provider = deepseek / dashscope / openai）</b><br>
 * 走标准 Tool Calling 循环，最多 {@value #MAX_TOOL_ROUNDS} 轮：
 * <pre>
 *   1. 把「工具说明书 + 知识库参考 + 用户问题」拼成系统提示词
 *   2. 模型返回。如果内容是 {"tool":"queryOrder","arguments":{...}} → 是工具调用
 *   3. 后端执行工具，把结果作为新一轮上下文回灌
 *   4. 重复 2-3，直到模型输出自然语言回答（不再包含工具调用 JSON）
 * </pre>
 * 为什么不依赖 spring-ai：一是版本迭代快、抽象层厚（出问题难定位），
 * 二是「让模型输出 JSON 工具调用」这套协议自己实现只要几十行，
 * 而且对模型能力要求更低（只要会输出 JSON 就行），可讲性也更强。
 *
 * <p><b>模式 B：本地 mock（离线演示 / CI）</b><br>
 * mock 模型不会真的做 function calling。所以这里用<b>规则意图识别</b>
 * （正则找订单号 {@code SO\d+} / 手机号，关键词判断「退款、工单、转人工」）
 * 来触发<b>完全相同的工具执行路径</b>。这样离线也能演示完整效果：
 * 查订单 → 复述订单状态 → 建工单 → 转人工，每一步都真实落库。
 * <b>两种模式的区别只在「谁决定调哪个工具」，工具本身的执行代码是同一份。</b>
 *
 * <p><b>安全边界</b>：所有工具的 tenantId 都来自 JWT（{@link TenantContext}），
 * 绝不接受模型输出的租户参数 —— 模型输出是不可信输入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerServiceAgent {

    public static final long SSE_TIMEOUT_MS = 300_000L;

    /** 工具调用最大轮数：防止模型陷入「反复调同一个工具」的死循环 */
    private static final int MAX_TOOL_ROUNDS = 3;

    /** 订单号：SO + 数字 */
    private static final Pattern ORDER_NO_PATTERN = Pattern.compile("(?i)\\bSO\\d{6,}\\b");
    /** 手机号：1 开头的 11 位 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\b1[3-9]\\d{9}\\b");
    /** 模型输出的工具调用 JSON */
    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
            "\\{[^{}]*\"(?:tool|name|function)\"\\s*:\\s*\"(\\w+)\"[^{}]*\\}", Pattern.DOTALL);

    private final ChatSessionService sessionService;
    private final CustomerServiceTools tools;
    private final ModelRouter modelRouter;
    private final AiServiceClient aiServiceClient;
    private final ChatModelStreamService streamService;
    private final KnowledgeBaseService kbService;
    private final AiCallLogService callLogService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    private final CsOrderRepository orderRepository;
    private final CsTicketRepository ticketRepository;
    private final CsHandoffRepository handoffRepository;

    // ==================================================================
    //  主入口
    // ==================================================================

    /**
     * 执行一次智能客服对话（SSE）。
     *
     * <p>事件协议与通用问答完全一致（meta / delta / done / error），
     * 这样前端可以复用同一套 SSE 解析代码。
     */
    public void stream(SseEmitter emitter, ChatDtos.StreamRequest req) {
        SseWriter writer = new SseWriter(emitter);
        Long tenantId = TenantContext.requireTenantId();
        Long userId = TenantContext.getUserIdOrNull();
        long startAt = System.currentTimeMillis();

        try {
            // 1) 会话：未知 uuid 自动建（scene=CS）
            ChatSession session = sessionService.requireOrCreate(
                    req.sessionUuid(), req.kbId(), req.providerCode(),
                    ChatSessionService.SCENE_CS, req.question());

            // 2) 判断走哪条路径
            ModelRouter.ProviderTarget target = modelRouter.resolvePreferred(
                    session.getTenantId(),
                    req.providerCode() == null || req.providerCode().isBlank()
                            ? session.getProviderCode() : req.providerCode());
            boolean mockMode = target.mock();

            String traceId = UUID.randomUUID().toString().replace("-", "");
            log.info("智能客服开始: sessionUuid={} provider={} mockMode={} question={}",
                    session.getSessionUuid(), target.describe(), mockMode, abbreviate(req.question()));

            // 3) meta 事件：先建会话上下文回复前端（含模式说明，演示时很直观）
            List<AiDtos.Citation> citations = new ArrayList<>();
            String kbContext = retrieveKbContext(session, req, citations);
            Map<String, Object> metaPayload = new LinkedHashMap<>();
            metaPayload.put("traceId", traceId);
            metaPayload.put("citations", citations);
            metaPayload.put("scene", "CS");
            metaPayload.put("mode", mockMode ? "RULE_INTENT" : "TOOL_CALLING");
            metaPayload.put("provider", target.providerCode());
            metaPayload.put("model", target.model());
            writer.meta(metaPayload);

            // 4) 生成回答（两种模式）
            String answer;
            List<CustomerServiceTools.ToolResult> executed;
            if (mockMode) {
                executed = new ArrayList<>();
                answer = mockLoop(session, req.question(), executed);
            } else {
                executed = new ArrayList<>();
                answer = realToolCallingLoop(session, req, target, kbContext, executed);
            }

            // 5) 模拟流式输出（mock 与真实模式统一按句推送，前端体验一致）
            streamAnswer(writer, answer);

            long elapsed = System.currentTimeMillis() - startAt;

            // 6) done
            Map<String, Object> donePayload = new LinkedHashMap<>();
            donePayload.put("elapsedMs", elapsed);
            donePayload.put("provider", mockMode ? "mock" : target.providerCode());
            donePayload.put("model", mockMode ? "mock-chat" : target.model());
            donePayload.put("toolCalls", executed.stream().map(CustomerServiceTools.ToolResult::toolName).toList());
            writer.done(donePayload);

            // 7) 落库 + 记账 + 审计
            String citationsJson = writeCitations(citations);
            sessionService.saveTurn(session.getTenantId(), session, req.question(), answer,
                    citationsJson, mockMode ? "mock-chat" : target.model(),
                    estimateTokens(req.question() + kbContext), estimateTokens(answer),
                    elapsed, traceId);

            for (CustomerServiceTools.ToolResult r : executed) {
                callLogService.recordSuccess(session.getTenantId(), userId, session.getId(), traceId,
                        "TOOL", target.providerCode(), "tool:" + r.toolName(),
                        0, 0, elapsed);
            }
            if (!mockMode) {
                callLogService.recordSuccess(session.getTenantId(), userId, session.getId(), traceId,
                        "CHAT", target.providerCode(), target.model(),
                        estimateTokens(req.question() + kbContext), estimateTokens(answer), elapsed);
            }

            auditService.log(AuditService.ACTION_CS_HANDOFF, "session:" + session.getSessionUuid(),
                    "智能客服对话 模式=" + (mockMode ? "RULE_INTENT" : "TOOL_CALLING")
                            + " 工具调用=" + executed.size() + " 回答字数=" + answer.length());

            log.info("智能客服完成: sessionUuid={} 模式={} 工具调用={} 回答字数={} 耗时={}ms traceId={}",
                    session.getSessionUuid(), mockMode ? "RULE_INTENT" : "TOOL_CALLING",
                    executed.size(), answer.length(), elapsed, traceId);

            writer.complete();

        } catch (BizException e) {
            log.warn("智能客服业务异常: {}", e.getMessage());
            writer.completeWithError(e.getMessage());
        } catch (Exception e) {
            log.error("智能客服执行失败", e);
            writer.completeWithError("服务异常：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    // ==================================================================
    //  模式 B：mock 规则意图识别（离线可演示完整工具链）
    // ==================================================================

    /**
     * mock 模式的意图识别与工具执行。
     *
     * <p>规则优先级与「链式调用」的设计说明：
     * <ol>
     *   <li><b>转人工</b>最高：用户明确要求人工就是最高优先级诉求，不该被别的规则拦下</li>
     *   <li><b>查订单</b>：有订单号/手机号，或问到物流发货</li>
     *   <li><b>建工单</b>：提到退款/投诉/报修/工单。
     *       <b>注意这里是链式调用</b> —— 如果一句话里既有订单号又要投诉
     *       （例如「订单 SO20261016002 退款三天没到账，我要投诉」），
     *       真实客服会<b>先查订单确认状态，再建工单并把订单号关联进去</b>。
     *       所以这里也允许一次对话触发两个工具，
     *       而不是命中第一个规则就返回 —— 那样会把用户的投诉诉求丢掉。</li>
     *   <li>都没命中 → 走知识库回答或引导用户补充信息</li>
     * </ol>
     */
    private String mockLoop(ChatSession session, String question, List<CustomerServiceTools.ToolResult> executed) {
        Long tenantId = session.getTenantId();
        Long sessionId = session.getId();
        String q = question == null ? "" : question;

        String orderNo = firstMatch(ORDER_NO_PATTERN, q);
        String phone = firstMatch(PHONE_PATTERN, q);

        // ---- 意图 1：转人工（最高优先级，直接返回）----
        if (containsAny(q, "转人工", "人工客服", "真人", "找客服", "转接")) {
            CustomerServiceTools.ToolResult r = tools.transferToHuman(tenantId, sessionId,
                    "用户主动要求：" + abbreviate(q));
            executed.add(r);
            return "已为你转接人工客服。\n\n"
                    + "· 转接原因：" + q.trim() + "\n"
                    + "· 当前状态：等待坐席接入（WAITING）\n\n"
                    + "人工客服会在工作时间内尽快接入，请保持页面打开。"
                    + "如果问题比较紧急，也可以先告诉我订单号，我帮你查一下当前状态。";
        }

        boolean asksOrder = containsAny(q, "订单", "物流", "发货", "到哪", "快递", "签收", "运单", "什么时候到");
        boolean wantsTicket = containsAny(q, "退款", "退货", "投诉", "报修", "工单", "申请售后", "坏了", "故障", "不满意");

        // ---- 意图 2：查订单（有订单号 / 手机号 / 问到订单）----
        String orderPart = null;
        if (orderNo != null || phone != null || asksOrder) {
            CustomerServiceTools.ToolResult r = tools.queryOrder(tenantId, orderNo, phone);
            executed.add(r);
            if (r.success()) {
                StringBuilder sb = new StringBuilder();
                sb.append("我帮你查到了订单信息：\n\n");
                sb.append(r.summary().replace("\n- ", "\n· ").replaceFirst("^- ", "· ")).append("\n\n");
                // 按状态给下一步建议 —— 这是「有用的客服」和「只会念数据的机器人」的区别
                sb.append(suggestNextStep(r));
                orderPart = sb.toString();
            } else if (!wantsTicket) {
                // 查不到订单，且用户也没有别的诉求 → 给出引导，流程结束
                return "抱歉，暂时没有查到对应的订单。\n\n"
                        + r.summary() + "\n\n"
                        + "你可以：\n"
                        + "· 核对一下订单号（格式是 SO + 数字，例如 SO20261016001）\n"
                        + "· 或者告诉我下单时用的手机号，我按手机号查\n"
                        + "· 也可以直接回复「转人工」，我帮你转接人工客服。";
            }
        }

        // ---- 意图 3：退款 / 工单 / 投诉（可以和意图 2 叠加，形成链式调用）----
        if (wantsTicket) {
            String priority = containsAny(q, "投诉", "紧急", "一直", "多次", "三天", "太久") ? "HIGH" : "NORMAL";
            String title = buildTicketTitle(q, orderNo);
            CustomerServiceTools.ToolResult r = tools.createTicket(tenantId, session.getUserId(),
                    orderNo, title, q, priority);
            executed.add(r);
            if (r.success()) {
                Map<?, ?> payload = (Map<?, ?>) r.payload();
                StringBuilder sb = new StringBuilder();
                if (orderPart != null) {
                    // 先给订单信息，再给工单结果 —— 这就是「链式工具调用」的完整呈现
                    sb.append(orderPart).append("\n");
                }
                sb.append("已经为你创建工单，客服会跟进处理。\n\n")
                        .append("· 工单号：").append(payload.get("ticketNo")).append("\n")
                        .append("· 问题标题：").append(payload.get("title")).append("\n")
                        .append("· 优先级：").append(payload.get("priority")).append("\n")
                        .append("· 当前状态：待处理（OPEN）\n");
                if (payload.get("orderNo") != null) {
                    sb.append("· 关联订单：").append(payload.get("orderNo")).append("\n");
                }
                sb.append("\n请记好工单号，后续可以用它查询进度。")
                        .append("如果需要更快的响应，可以直接回复「转人工」。");
                return sb.toString();
            }
            return (orderPart == null ? "" : orderPart + "\n")
                    + "创建工单时遇到问题：" + r.summary();
        }

        // ---- 意图 4：查到了订单但没有其它诉求 ----
        if (orderPart != null) {
            return orderPart + "\n需要我帮你创建工单跟进，或者转接人工客服都可以直接说。";
        }

        // ---- 意图 5：没有命中规则 → 引导补充信息 ----
        return "你好，我是智能客服助手，可以帮你处理以下事情：\n\n"
                + "· 查询订单：把订单号（例如 SO20261016001）或下单手机号发给我\n"
                + "· 申请售后：说明遇到的问题和订单号，我帮你建工单\n"
                + "· 转人工：直接回复「转人工」，我马上为你转接\n\n"
                + "当前是离线演示模式（本地 mock 模型），工具调用链路是真实执行的，"
                + "查询结果都来自数据库。";
    }

    /** 按订单状态给下一步建议 */
    private String suggestNextStep(CustomerServiceTools.ToolResult r) {
        Object payload = r.payload();
        String status = null;
        if (payload instanceof Map<?, ?> m) {
            Object v = m.get("orderStatus");
            status = v == null ? null : String.valueOf(v);
        }
        if (status == null) {
            return "如需进一步处理，可以告诉我具体诉求。";
        }
        return switch (status) {
            case "PAID" -> "当前订单已付款、仓库尚未发货。正常情况下 24 小时内会安排出库，请耐心等待。";
            case "SHIPPED" -> "包裹已发出，可以用上面的物流单号在快递官网查询实时位置。";
            case "DELIVERED" -> "订单已签收。如果商品有问题，我可以帮你创建售后工单。";
            case "REFUNDING" -> "退款正在处理中，一般 1-3 个工作日回到原支付渠道。超时未到账的话，我可以帮你建工单催一下。";
            case "CLOSED" -> "该订单已关闭。如需重新下单或有其它问题，可以继续告诉我。";
            default -> "如需进一步处理，可以告诉我具体诉求。";
        };
    }

    // ==================================================================
    //  模式 A：真实模型的 Tool Calling 循环
    // ==================================================================

    /**
     * 真实的「模型决定工具 → 后端执行 → 结果回灌」循环。
     */
    private String realToolCallingLoop(ChatSession session, ChatDtos.StreamRequest req,
                                       ModelRouter.ProviderTarget target, String kbContext,
                                       List<CustomerServiceTools.ToolResult> executed) {
        Long tenantId = session.getTenantId();
        Long userId = session.getUserId();

        String systemPrompt = buildSystemPrompt(kbContext);
        List<ChatModel.ChatTurn> history = new ArrayList<>();
        String currentUserMessage = req.question();

        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            final String msgForThisRound = currentUserMessage;
            ChatModel.ChatResult result = modelRouter.chatWithFallback(
                    tenantId, userId, session.getId(), null, target.providerCode(), "TOOL",
                    model -> model.chat(systemPrompt, msgForThisRound, history, 0.2));

            String content = result.content() == null ? "" : result.content();
            ToolCall call = parseToolCall(content);

            if (call == null) {
                // 模型给出了自然语言回答，循环结束
                log.debug("[客服] 第 {} 轮模型直接作答，长度={}", round, content.length());
                return content;
            }

            log.info("[客服] 第 {} 轮模型请求调用工具: {} 参数={}", round, call.name(), call.arguments());

            // 执行工具：tenantId / sessionId 一律来自服务端，绝不用模型给的
            CustomerServiceTools.ToolResult toolResult = executeTool(tenantId, userId, session.getId(), call);
            executed.add(toolResult);

            // 结果回灌：把工具结果作为「用户消息」追加（而非 tool role），
            // 因为多数 OpenAI 兼容端点对 tool role 有额外约束（要配 tool_call_id），
            // 用普通消息回灌兼容性最好，也不会因为某一家的实现差异而失败。
            history.add(ChatModel.ChatTurn.assistant(content));
            history.add(ChatModel.ChatTurn.user(
                    "【工具调用结果】\n工具: " + toolResult.toolName()
                            + "\n执行结果: " + (toolResult.success() ? "成功" : "失败")
                            + "\n数据: " + toJson(toolResult.payload())
                            + "\n摘要: " + toolResult.summary()
                            + "\n\n请基于以上真实数据，用自然、礼貌的中文回答用户。"
                            + "需要复述订单号、商品、状态、物流单号等关键字段。"
                            + "如果还需要调用其它工具，继续输出工具调用 JSON（一次只调一个）。\n"
                            + "【用户问题】"));
            currentUserMessage = req.question();
        }

        // 超过最大轮数：兜底返回已获得的工具结果，避免用户拿到空响应
        log.warn("[客服] 达到最大工具调用轮数 {}，直接汇总工具结果作答", MAX_TOOL_ROUNDS);
        StringBuilder sb = new StringBuilder("我已经查到了以下信息，供你参考：\n\n");
        for (CustomerServiceTools.ToolResult r : executed) {
            sb.append("· ").append(r.summary()).append('\n');
        }
        sb.append("\n如果还需要进一步处理，可以回复「转人工」，我帮你转接人工客服。");
        return sb.toString();
    }

    /** 按工具名分派执行 */
    private CustomerServiceTools.ToolResult executeTool(Long tenantId, Long userId, Long sessionId, ToolCall call) {
        Map<String, Object> args = call.arguments() == null ? Map.of() : call.arguments();
        try {
            return switch (call.name()) {
                case CustomerServiceTools.TOOL_QUERY_ORDER -> tools.queryOrder(
                        tenantId, str(args.get("orderNo")), str(args.get("phone")));
                case CustomerServiceTools.TOOL_CREATE_TICKET -> tools.createTicket(
                        tenantId, userId,
                        str(args.get("orderNo")), str(args.get("title")),
                        str(args.get("content")), str(args.get("priority")));
                case CustomerServiceTools.TOOL_TRANSFER_TO_HUMAN -> tools.transferToHuman(
                        tenantId, sessionId, str(args.get("reason")));
                default -> new CustomerServiceTools.ToolResult(call.name(), false, Map.of(),
                        "未知工具名：" + call.name() + "。可用工具：" + tools.toolsSpec());
            };
        } catch (Exception e) {
            // 工具执行异常不能让整条对话挂掉：把错误如实回灌给模型，让它向用户解释
            log.error("[客服] 工具 {} 执行异常", call.name(), e);
            return new CustomerServiceTools.ToolResult(call.name(), false, Map.of("error", String.valueOf(e.getMessage())),
                    "工具执行失败：" + e.getMessage());
        }
    }

    /**
     * 从模型输出里解析工具调用。
     *
     * <p>兼容多种常见格式（不同模型输出习惯不一样）：
     * <pre>
     *   {"tool":"queryOrder","arguments":{"orderNo":"SO123"}}
     *   {"name":"queryOrder","arguments":{...}}
     *   {"function":{"name":"queryOrder","arguments":{...}}}
     *   以及被 ```json 代码块包裹的情况
     * </pre>
     * 只要整段输出里出现了工具名，就认为是工具调用请求。
     */
    private ToolCall parseToolCall(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.trim();
        // 去掉 markdown 代码块围栏
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        Matcher m = TOOL_CALL_PATTERN.matcher(trimmed);
        while (m.find()) {
            String toolName = m.group(1);
            if (!isKnownTool(toolName)) {
                continue;
            }
            Map<String, Object> args = extractArguments(trimmed, m.start(), m.end());
            return new ToolCall(toolName, args);
        }
        return null;
    }

    /** 从工具调用 JSON 附近提取 arguments 对象 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractArguments(String content, int start, int end) {
        // 先尝试把从 start 开始的完整 JSON 解析出来
        for (int tryEnd = content.length(); tryEnd > end; tryEnd--) {
            String candidate = content.substring(start, tryEnd);
            try {
                JsonNode node = objectMapper.readTree(candidate);
                JsonNode argsNode = node.has("arguments") ? node.get("arguments")
                        : (node.has("parameters") ? node.get("parameters")
                        : (node.has("function") && node.get("function").has("arguments")
                        ? node.get("function").get("arguments") : null));
                if (argsNode == null || argsNode.isNull()) {
                    return Map.of();
                }
                if (argsNode.isTextual()) {
                    // 有些模型把 arguments 序列化成字符串
                    return objectMapper.readValue(argsNode.asText(), Map.class);
                }
                return objectMapper.convertValue(argsNode, Map.class);
            } catch (Exception ignored) {
                // 继续缩短候选串再试
            }
        }
        return Map.of();
    }

    private boolean isKnownTool(String name) {
        return CustomerServiceTools.TOOL_QUERY_ORDER.equals(name)
                || CustomerServiceTools.TOOL_CREATE_TICKET.equals(name)
                || CustomerServiceTools.TOOL_TRANSFER_TO_HUMAN.equals(name);
    }

    /** 构造系统提示词：工具说明书 + 回答格式约束 + 知识库参考 */
    private String buildSystemPrompt(String kbContext) {
        return """
                你是电商平台的智能客服助手，语气礼貌、简洁、专业。

                【可用工具】
                %s

                【调用工具的方式】
                如果需要查询订单、创建工单或转人工，只输出一个 JSON，不要有任何其它文字：
                {"tool":"工具名","arguments":{"参数名":"参数值"}}

                调用规则：
                - 一次只调用一个工具，拿到结果后再决定下一步
                - 用户给了订单号（形如 SO20261016001）或手机号，就用 queryOrder 查
                - 查到订单后，请复述订单号、商品、状态、物流单号等关键字段，再给结论与建议
                - 用户要退款/投诉/报修且无法立即解决时，用 createTicket 建单
                - 用户明确要求人工，或连续两轮无法解决时，用 transferToHuman
                - 不要编造订单信息，一切以工具返回的数据为准

                %s
                """.formatted(tools.toolsSpec(),
                (kbContext == null || kbContext.isBlank()) ? "" : "【知识库参考】\n" + kbContext + "\n");
    }

    // ==================================================================
    //  知识库上下文 & 流式输出
    // ==================================================================

    /** 如果会话绑了知识库，先检索一段上下文拼进提示词（客服也支持 RAG 增强） */
    private String retrieveKbContext(ChatSession session, ChatDtos.StreamRequest req,
                                     List<AiDtos.Citation> citations) {
        Long kbId = session.getKbId() != null ? session.getKbId() : req.kbId();
        if (kbId == null) {
            return "";
        }
        try {
            KnowledgeBase kb = kbService.requireKb(session.getTenantId(), kbId);
            AiDtos.RetrieveResponse rr = aiServiceClient.retrieve(
                    kbId, req.question(), kb.getTopK(),
                    req.strategy() == null || req.strategy().isBlank() ? "HYBRID_RERANK" : req.strategy());
            if (rr.results() == null || rr.results().isEmpty()) {
                return "";
            }
            StringBuilder ctx = new StringBuilder();
            int idx = 1;
            for (AiDtos.RetrievedChunk c : rr.results()) {
                ctx.append('[').append(idx).append("] ")
                        .append(c.docName() == null ? ("文档" + c.docId()) : c.docName());
                if (c.pageNo() != null) {
                    ctx.append(" 第").append(c.pageNo()).append("页");
                }
                ctx.append('\n').append(c.content()).append("\n\n");
                citations.add(new AiDtos.Citation(idx, c.chunkId(), c.docId(), c.docName(),
                        c.pageNo(), c.chunkIndex(), c.score(),
                        abbreviate(c.content())));
                idx++;
            }
            return ctx.toString();
        } catch (Exception e) {
            // 检索失败不影响客服主流程（工具调用才是客服的核心能力）
            log.warn("客服知识库检索失败（继续走工具链）: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 把回答按句切分后逐段推送，模拟真实的流式体验。
     *
     * <p>为什么要这么做：mock 模式下回答是一次性生成的，
     * 但前端的流式渲染逻辑需要 delta 事件才能工作。
     * 按句子切片推送，既能让前端验证流式链路，
     * 又能让演示效果与真实模型一致（逐字出现而不是「啪」一下全出来）。
     */
    private void streamAnswer(SseWriter writer, String answer) {
        if (answer == null || answer.isEmpty()) {
            writer.delta("");
            return;
        }
        for (String seg : splitForStreaming(answer)) {
            writer.delta(seg);
            if (writer.isFinished()) {
                return;
            }
        }
    }

    /** 按句末标点切分，并把过短的分片合并，避免每秒推几十个字符的碎片 */
    private List<String> splitForStreaming(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            buf.append(c);
            boolean boundary = (c == '\n' || c == '。' || c == '！' || c == '？' || c == '；');
            if (boundary && buf.length() >= 12) {
                out.add(buf.toString());
                buf.setLength(0);
            } else if (buf.length() >= 60) {
                // 长句也要切，避免单段过大
                out.add(buf.toString());
                buf.setLength(0);
            }
        }
        if (!buf.isEmpty()) {
            out.add(buf.toString());
        }
        return out;
    }

    // ==================================================================
    //  辅助
    // ==================================================================

    private record ToolCall(String name, Map<String, Object> arguments) {
    }

    private String writeCitations(List<AiDtos.Citation> citations) {
        if (citations == null || citations.isEmpty()) {
            return null;
        }
        try {
            List<Map<String, Object>> list = new ArrayList<>(citations.size());
            for (AiDtos.Citation c : citations) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", c.index());
                m.put("chunkId", c.chunkId());
                m.put("docId", c.docId());
                m.put("docName", c.docName());
                m.put("pageNo", c.pageNo());
                m.put("chunkIndex", c.chunkIndex());
                m.put("score", c.score());
                m.put("snippet", c.snippet());
                list.add(m);
            }
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    /** 工单标题：优先用订单号+问题类型，保证列表里一眼能看懂 */
    private String buildTicketTitle(String question, String orderNo) {
        String core;
        if (containsAny(question, "退款", "退货")) {
            core = "申请退款";
        } else if (containsAny(question, "投诉")) {
            core = "用户投诉";
        } else if (containsAny(question, "报修", "故障", "坏了")) {
            core = "产品故障报修";
        } else {
            core = "售后咨询";
        }
        return (orderNo == null ? "" : orderNo + " ") + core;
    }

    private static String firstMatch(Pattern p, String text) {
        if (text == null) {
            return null;
        }
        Matcher m = p.matcher(text);
        return m.find() ? m.group() : null;
    }

    private static boolean containsAny(String text, String... keys) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase();
        for (String k : keys) {
            if (lower.contains(k.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int estimateTokens(String text) {
        return text == null || text.isEmpty() ? 0 : Math.max(1, text.length() / 2);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return t.length() <= 60 ? t : t.substring(0, 60) + "...";
    }

    /** 供管理端/调试使用：当前租户下订单/工单/转人工数量概览 */
    public Map<String, Object> csOverview(Long tenantId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderCount", orderRepository.countByTenantId(tenantId));
        m.put("ticketCount", ticketRepository.countByTenantId(tenantId));
        m.put("handoffCount", handoffRepository.findByTenantIdOrderByIdDesc(tenantId).size());
        return m;
    }
}
