package com.yit.rag.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yit.rag.api.dto.chat.ChatDtos;
import com.yit.rag.common.exception.BizException;
import com.yit.rag.common.tenant.TenantContext;
import com.yit.rag.domain.chat.ChatSession;
import com.yit.rag.domain.kb.KnowledgeBase;
import com.yit.rag.service.ai.AiServiceClient;
import com.yit.rag.service.ai.dto.AiDtos;
import com.yit.rag.service.ai.model.ModelRouter;
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
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通用问答服务 —— {@code /api/chat/stream} 的实现。
 *
 * <p>这个类解决什么问题：把「前端 SSE 请求 → 调 AI 服务 → 事件原样透传 → 结束后落库」
 * 这条链路的每一环都处理干净。具体要处理五件事：
 *
 * <ol>
 *   <li><b>会话容错</b>：前端传的 sessionUuid 可能库里还不存在（它自己 randomUUID 生成的），
 *       这里自动落库建会话，不让前端 400。</li>
 *   <li><b>知识库校验</b>：kbId 必须属于当前租户，否则就是水平越权。</li>
 *   <li><b>事件原样透传</b>：AI 服务的 meta / delta / done / error 直接转发，
 *       后端不解析正文、不做二次包装 —— 这样 AI 服务调整提示词或换模型，
 *       后端与前端都不需要改。</li>
 *   <li><b>结束后落库</b>：流结束后一次性写 user + assistant 两条消息，
 *       assistant 带 citations JSON、tokens、latency、traceId，保证事后可复盘。</li>
 *   <li><b>多模型 Fallback</b>：交给 {@link ChatModelStreamService}，
 *       连接阶段失败对前端完全透明。</li>
 * </ol>
 *
 * <p>为什么这里不用 {@code @Transactional}：SSE 是长连接，
 * 如果在事务里等模型生成完，数据库连接会被占用几十秒。
 * 并发一上来连接池就爆了。所以「流式过程中不占事务，结束时才开短事务落库」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    /** SSE 超时：给足 5 分钟，覆盖长回答 */
    private static final long SSE_TIMEOUT_MS = 300_000L;

    private final ChatSessionService sessionService;
    private final ChatModelStreamService streamService;
    private final KnowledgeBaseService kbService;
    private final AiServiceClient aiServiceClient;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /**
     * 创建 SSE 流并交给调用方执行。
     *
     * <p>注意这里只创建 emitter 并返回，真正的执行由 {@code execute} 完成 ——
     * 这样 Controller 可以先返回 SseEmitter（让 HTTP 响应头立刻发出去），
     * 再在独立线程里跑耗时逻辑，前端能马上进入「等待流」状态而不是卡在连接建立上。
     */
    public SseEmitter createEmitter() {
        return new SseEmitter(SSE_TIMEOUT_MS);
    }

    /**
     * 执行一次流式问答。
     *
     * @param emitter 由 Controller 创建并已注册回调
     * @param req     前端请求体
     */
    public void stream(SseEmitter emitter, ChatDtos.StreamRequest req) {
        SseWriter writer = new SseWriter(emitter);
        Long tenantId = TenantContext.requireTenantId();
        Long userId = TenantContext.getUserIdOrNull();

        try {
            // 1) 会话：未知 uuid 自动建（兼容前端自己生成 UUID 的用法）
            ChatSession session = sessionService.requireOrCreate(
                    req.sessionUuid(), req.kbId(), req.providerCode(),
                    ChatSessionService.SCENE_CHAT, req.question());

            // 2) 知识库（可选）：校验归属并取检索参数
            Long kbId = session.getKbId() != null ? session.getKbId() : req.kbId();
            Integer topK = null;
            if (kbId != null) {
                KnowledgeBase kb = kbService.requireKb(session.getTenantId(), kbId);
                topK = kb.getTopK();
            }

            log.info("开始流式问答: sessionUuid={} kbId={} provider={} question={}",
                    session.getSessionUuid(), kbId, req.providerCode(), abbreviate(req.question()));

            // 3) 调 AI 服务 + Fallback，事件原样透传
            AtomicReference<AiDtos.StreamMeta> metaRef = new AtomicReference<>();
            AtomicReference<AiDtos.StreamDone> doneRef = new AtomicReference<>();
            AtomicReference<String> errorRef = new AtomicReference<>();
            StringBuilder answerBuf = new StringBuilder();
            int[] deltaCount = {0};
            final Integer finalTopK = topK;
            final Long finalKbId = kbId;
            final long startAt = System.currentTimeMillis();
            final String[] usedProvider = {null};
            final String[] usedModel = {null};

            AiServiceClient.StreamHandler handler = new AiServiceClient.StreamHandler() {
                @Override
                public void onMeta(AiDtos.StreamMeta meta) {
                    metaRef.set(meta);
                    // 原样透传给前端（字段名保持 AI 服务的 camelCase：traceId / citations）
                    writer.meta(meta);
                }

                @Override
                public void onDelta(String content) {
                    answerBuf.append(content);
                    deltaCount[0]++;
                    writer.delta(content);
                }

                @Override
                public void onDone(AiDtos.StreamDone done) {
                    doneRef.set(done);
                }

                @Override
                public void onError(String message) {
                    errorRef.set(message);
                    writer.error(message);
                }
            };

            streamService.executeWithFallback(session.getTenantId(), userId, session.getId(),
                    req.providerCode() == null ? session.getProviderCode() : req.providerCode(),
                    "CHAT", handler,
                    (target, forwarder) -> {
                        usedProvider[0] = target.providerCode();
                        usedModel[0] = target.model();
                        AiDtos.RagAskRequest ask = new AiDtos.RagAskRequest(
                                finalKbId,
                                req.question(),
                                finalTopK,
                                req.strategy() == null || req.strategy().isBlank()
                                        ? "HYBRID_RERANK" : req.strategy(),
                                target.providerCode(),
                                null,   // systemPrompt 交给 AI 服务用数据库里的模板渲染
                                null);
                        // 关键：把 AI 服务的事件交给 forwarder（而不是直接给 handler）。
                        // forwarder 负责「攒到第一个 delta 再放行」，这是流式 Fallback
                        // 不产生重复内容的前提；它最终会调用 handler 完成透传与累计。
                        return aiServiceClient.ragStream(ask, new AiServiceClient.StreamHandler() {
                            @Override
                            public void onMeta(AiDtos.StreamMeta meta) {
                                forwarder.meta(meta);
                            }

                            @Override
                            public void onDelta(String content) {
                                forwarder.delta(content);
                            }

                            @Override
                            public void onDone(AiDtos.StreamDone done) {
                                handler.onDone(done);
                            }

                            @Override
                            public void onError(String message) {
                                handler.onError(message);
                            }
                        });
                    });

            long elapsed = System.currentTimeMillis() - startAt;

            // 4) done 收尾：把最终用的模型名告知前端（可能就是 Fallback 后的那家）
            AiDtos.StreamDone done = doneRef.get();
            Map<String, Object> donePayload = new LinkedHashMap<>();
            donePayload.put("elapsedMs", done != null && done.elapsedMs() != null ? done.elapsedMs() : elapsed);
            donePayload.put("provider", usedProvider[0] != null ? usedProvider[0]
                    : (done == null ? "unknown" : done.provider()));
            donePayload.put("model", usedModel[0] != null ? usedModel[0]
                    : (done == null ? null : done.model()));
            writer.done(donePayload);

            // 5) 落库（独立短事务，不在流式过程中持有连接）
            AiDtos.StreamMeta meta = metaRef.get();
            String traceId = meta == null ? null : meta.traceId();
            String citationsJson = writeCitations(meta);
            int promptTokens = estimateTokens(req.question());
            int completionTokens = estimateTokens(answerBuf.toString());
            sessionService.saveTurn(session.getTenantId(), session, req.question(), answerBuf.toString(),
                    citationsJson, usedModel[0], promptTokens, completionTokens, elapsed, traceId);

            // 6) 记账（tokens 由后端估算，因为流式接口的 done 事件不含 usage）
            streamService.recordSuccess(session.getTenantId(), userId, session.getId(), traceId,
                    "CHAT", ModelRouter.loggingTarget(usedProvider[0], usedModel[0]),
                    promptTokens, completionTokens, elapsed);

            auditService.log(AuditService.ACTION_CHAT_STREAM, "session:" + session.getSessionUuid(),
                    "流式问答 增量段数=" + deltaCount[0] + " 回答字数=" + answerBuf.length()
                            + " provider=" + usedProvider[0]);

            log.info("流式问答完成: sessionUuid={} provider={} 增量段数={} 回答字数={} 耗时={}ms traceId={}",
                    session.getSessionUuid(), usedProvider[0], deltaCount[0], answerBuf.length(), elapsed, traceId);

            writer.complete();

        } catch (BizException e) {
            log.warn("流式问答业务异常: {}", e.getMessage());
            writer.completeWithError(e.getMessage());
        } catch (Exception e) {
            log.error("流式问答失败", e);
            writer.completeWithError("服务异常：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    // ==================================================================
    //  工具方法
    // ==================================================================

    /** 把 citations 序列化成 JSON 字符串入库（库字段是 JSON 类型） */
    private String writeCitations(AiDtos.StreamMeta meta) {
        if (meta == null || meta.citations() == null || meta.citations().isEmpty()) {
            return null;
        }
        try {
            // 转换一下字段名：库里约定用 {docId,docName,pageNo,chunkIndex,score,snippet}
            List<Map<String, Object>> list = new ArrayList<>(meta.citations().size());
            for (AiDtos.Citation c : meta.citations()) {
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
            log.warn("引用序列化失败，落库为空: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 粗略估算 token 数：中文按 2 字符 ≈ 1 token。
     *
     * <p>为什么流式场景要自己估算：AI 服务的 SSE {@code done} 事件只带了
     * {@code elapsedMs/provider/model}，没有 usage 字段（契约如此）。
     * 而成本统计必须有 token 数，所以用「字符数 / 2」近似 ——
     * 这个精度对成本报表足够（误差在 20% 以内，且是系统性偏差，可以做系数校正）。
     * 需要精确值时应该改用非流式的 {@code /api/v1/rag/ask} 接口拿 usage。
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 2);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() <= 60 ? t : t.substring(0, 60) + "...";
    }
}
