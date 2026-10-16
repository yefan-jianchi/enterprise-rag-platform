package com.yit.rag.service.chat;

import com.yit.rag.api.dto.chat.ChatDtos;
import com.yit.rag.common.exception.BizException;
import com.yit.rag.common.tenant.TenantContext;
import com.yit.rag.domain.chat.ChatMessage;
import com.yit.rag.domain.chat.ChatSession;
import com.yit.rag.repository.chat.ChatMessageRepository;
import com.yit.rag.repository.chat.ChatSessionRepository;
import com.yit.rag.service.kb.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 会话服务 —— 会话与消息的生命周期管理。
 *
 * <p>这个类解决什么问题：把「会话/消息」的读写收敛到一处，特别是解决两个实际痛点：
 *
 * <p><b>痛点 1：前端自己生成 sessionUuid。</b>
 * 契约里没有「客服建会话」接口，前端在客服页用 {@code crypto.randomUUID()} 自己造一个
 * UUID 就直接 POST 流式接口。如果后端严格要求「UUID 必须已存在于 chat_session」，
 * 客服功能会直接 400。所以这里提供 {@link #requireOrCreate}：
 * <b>未知 UUID 自动落库建会话</b>，让前端少一次往返、接口更宽容。
 *
 * <p><b>痛点 2：流式问答的消息落库时机。</b>
 * 流式回答过程中前端已经收到了内容，此时如果写库失败，用户看到的内容就与历史记录不一致。
 * 所以写消息的方法用<b>独立事务（REQUIRES_NEW）</b>，并且放在流结束后一次性写两条
 * （user + assistant），保证「要么两条都在，要么两条都不在」。
 *
 * <p>为什么 {@code sessionUuid} 用 UUID 而不是自增 id 对外暴露：
 * 自增 id 可枚举，攻击者从 1 开始遍历就能读到别人的会话。UUID 不可枚举，
 * 再加上每次查询都带 tenantId 校验，形成双保险。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    public static final String SCENE_CHAT = "CHAT";
    public static final String SCENE_CS = "CS";

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final KnowledgeBaseService kbService;

    // ==================================================================
    //  会话
    // ==================================================================

    /**
     * 按 sessionUuid 取会话；不存在则<b>自动创建</b>。
     *
     * <p>这是「前端自己生成 UUID」场景的关键兼容点。创建时：
     * <ul>
     *   <li>{@code tenantId / userId} 取当前登录用户（从 JWT 来，绝不信任前端传的）</li>
     *   <li>{@code scene} 由调用方指定（CHAT 或 CS）</li>
     *   <li>{@code title} 用问题前 20 字，这样会话列表里能一眼看出聊了什么；
     *       传入空问题（例如只是刷新会话）时给个默认标题</li>
     *   <li>{@code kbId} 如果传了，会校验它属于当前租户（防越权）</li>
     * </ul>
     */
    @Transactional
    public ChatSession requireOrCreate(String sessionUuid, Long kbId, String providerCode,
                                       String scene, String question) {
        Long tenantId = TenantContext.requireTenantId();
        Long userId = TenantContext.getUserIdOrNull();
        if (userId == null) {
            throw BizException.unauthorized("未登录，无法创建会话");
        }

        String uuid = (sessionUuid == null || sessionUuid.isBlank())
                ? UUID.randomUUID().toString()
                : sessionUuid.trim();

        var existing = sessionRepository.findBySessionUuidAndTenantId(uuid, tenantId);
        if (existing.isPresent()) {
            ChatSession s = existing.get();
            // 会话上没绑知识库、本次请求带了 → 补上（前端可能在会话中途才选知识库）
            if (s.getKbId() == null && kbId != null) {
                validateKb(tenantId, kbId);
                s.setKbId(kbId);
                sessionRepository.save(s);
            }
            return s;
        }

        // ---- 自动建会话（前端生成的 UUID 第一次出现）----
        if (kbId != null) {
            validateKb(tenantId, kbId);
        }
        String effectiveScene = (scene == null || scene.isBlank()) ? SCENE_CHAT : scene.toUpperCase();
        ChatSession session = new ChatSession();
        session.setTenantId(tenantId);
        session.setUserId(userId);
        session.setSessionUuid(uuid);
        session.setKbId(kbId);
        session.setProviderCode(providerCode == null || providerCode.isBlank() ? "deepseek" : providerCode);
        session.setScene(effectiveScene);
        session.setMessageCount(0);
        session.setTitle(buildTitle(question));
        session = sessionRepository.save(session);

        log.info("自动创建会话（前端传入的未知 sessionUuid）: uuid={} scene={} tenantId={} userId={} title={}",
                uuid, effectiveScene, tenantId, userId, session.getTitle());
        return session;
    }

    /**
     * 显式创建会话（{@code POST /api/chat/session}）。
     */
    @Transactional
    public ChatDtos.SessionItem create(ChatDtos.CreateSessionRequest req) {
        Long tenantId = TenantContext.requireTenantId();
        Long userId = TenantContext.getUserIdOrNull();
        if (userId == null) {
            throw BizException.unauthorized("未登录，无法创建会话");
        }
        if (req.kbId() != null) {
            validateKb(tenantId, req.kbId());
        }
        String scene = (req.scene() == null || req.scene().isBlank())
                ? SCENE_CHAT : req.scene().toUpperCase();

        ChatSession session = new ChatSession();
        session.setTenantId(tenantId);
        session.setUserId(userId);
        session.setSessionUuid(UUID.randomUUID().toString());
        session.setKbId(req.kbId());
        session.setProviderCode(req.providerCode() == null || req.providerCode().isBlank()
                ? "deepseek" : req.providerCode());
        session.setScene(scene);
        session.setMessageCount(0);
        session.setTitle(req.title() == null || req.title().isBlank() ? "新会话" : req.title().trim());
        session = sessionRepository.save(session);
        return toItem(session);
    }

    /**
     * 当前用户的会话列表。
     *
     * <p><b>兼容性要求</b>：不传 {@code scene} 时返回<b>全部</b>会话（前端当前就是这么用的），
     * 传了才按场景过滤（{@code ?scene=CS} / {@code ?scene=CHAT}）。
     * 默认行为不能变，否则前端现有代码会拿到空列表。
     */
    @Transactional(readOnly = true)
    public List<ChatDtos.SessionItem> list(String scene) {
        Long tenantId = TenantContext.requireTenantId();
        Long userId = TenantContext.getUserIdOrNull();
        if (userId == null) {
            throw BizException.unauthorized("未登录");
        }
        List<ChatSession> sessions = (scene == null || scene.isBlank())
                ? sessionRepository.findByTenantIdAndUserIdOrderByIdDesc(tenantId, userId)
                : sessionRepository.findByTenantIdAndUserIdAndSceneOrderByIdDesc(
                        tenantId, userId, scene.trim().toUpperCase());
        return sessions.stream().map(this::toItem).toList();
    }

    /**
     * 取会话（带租户校验）。
     */
    @Transactional(readOnly = true)
    public ChatSession require(String sessionUuid) {
        Long tenantId = TenantContext.requireTenantId();
        return sessionRepository.findBySessionUuidAndTenantId(sessionUuid, tenantId)
                .orElseThrow(() -> BizException.notFound("会话不存在或无权访问: " + sessionUuid));
    }

    /** 按 uuid 取会话，不存在返回 null（供容错路径使用） */
    @Transactional(readOnly = true)
    public ChatSession findOrNull(String sessionUuid) {
        Long tenantId = TenantContext.getTenantIdOrNull();
        if (tenantId == null || sessionUuid == null) {
            return null;
        }
        return sessionRepository.findBySessionUuidAndTenantId(sessionUuid, tenantId).orElse(null);
    }

    // ==================================================================
    //  消息
    // ==================================================================

    /**
     * 某会话的历史消息。
     */
    @Transactional(readOnly = true)
    public List<ChatDtos.MessageItem> messages(String sessionUuid) {
        ChatSession session = require(sessionUuid);
        return messageRepository.findByTenantIdAndSessionIdOrderByIdAsc(session.getTenantId(), session.getId())
                .stream().map(this::toItem).toList();
    }

    /**
     * 一次问答落两条消息（user + assistant），并更新会话的消息计数与标题。
     *
     * <p>为什么用 REQUIRES_NEW：流式接口的主流程不在事务里（SSE 是长连接，
     * 挂着一个数据库事务等模型生成完，会把连接池占满）。所以这里单开事务把两条消息写进去，
     * 提交完立刻释放连接。
     *
     * @return 落库后的 assistant 消息（供调用方回传 id 等）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChatMessage saveTurn(Long tenantId, ChatSession session, String question, String answer,
                                String citationsJson, String model,
                                int promptTokens, int completionTokens,
                                long latencyMs, String traceId) {
        // 1) 用户消息
        ChatMessage userMsg = new ChatMessage();
        userMsg.setTenantId(tenantId);
        userMsg.setSessionId(session.getId());
        userMsg.setRole(ChatMessage.ROLE_USER);
        userMsg.setContent(question == null ? "" : question);
        userMsg.setTraceId(truncate(traceId, 32));
        messageRepository.save(userMsg);

        // 2) 助手消息（带引用、token、耗时、traceId —— 全部可复盘）
        ChatMessage aiMsg = new ChatMessage();
        aiMsg.setTenantId(tenantId);
        aiMsg.setSessionId(session.getId());
        aiMsg.setRole(ChatMessage.ROLE_ASSISTANT);
        aiMsg.setContent(answer == null ? "" : answer);
        aiMsg.setCitations(citationsJson);
        aiMsg.setModel(model);
        aiMsg.setPromptTokens(Math.max(promptTokens, 0));
        aiMsg.setCompletionTokens(Math.max(completionTokens, 0));
        aiMsg.setLatencyMs((int) Math.min(Math.max(latencyMs, 0), Integer.MAX_VALUE));
        aiMsg.setTraceId(truncate(traceId, 32));
        aiMsg = messageRepository.save(aiMsg);

        // 3) 更新会话：消息数 +2；标题为空时用第一句话补上
        ChatSession managed = sessionRepository.findById(session.getId()).orElse(session);
        managed.setMessageCount((managed.getMessageCount() == null ? 0 : managed.getMessageCount()) + 2);
        if (managed.getTitle() == null || managed.getTitle().isBlank() || "新会话".equals(managed.getTitle())) {
            managed.setTitle(buildTitle(question));
        }
        sessionRepository.save(managed);

        log.debug("问答消息已落库: sessionId={} assistantMsgId={} tokens={}+{} latency={}ms traceId={}",
                session.getId(), aiMsg.getId(), promptTokens, completionTokens, latencyMs, traceId);
        return aiMsg;
    }

    /** 只落一条助手消息（智能客服工具链的场景：答案由后端拼装） */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChatMessage saveAssistantOnly(Long tenantId, Long sessionId, String answer,
                                         String citationsJson, String model, String traceId) {
        ChatMessage aiMsg = new ChatMessage();
        aiMsg.setTenantId(tenantId);
        aiMsg.setSessionId(sessionId);
        aiMsg.setRole(ChatMessage.ROLE_ASSISTANT);
        aiMsg.setContent(answer == null ? "" : answer);
        aiMsg.setCitations(citationsJson);
        aiMsg.setModel(model);
        aiMsg.setTraceId(truncate(traceId, 32));
        return messageRepository.save(aiMsg);
    }

    // ==================================================================
    //  工具方法
    // ==================================================================

    /** 用问题前 20 字做会话标题 */
    private String buildTitle(String question) {
        if (question == null || question.isBlank()) {
            return "新会话";
        }
        String t = question.replaceAll("\\s+", " ").trim();
        return t.length() <= 20 ? t : t.substring(0, 20);
    }

    /** 校验知识库属于当前租户（防水平越权） */
    private void validateKb(Long tenantId, Long kbId) {
        kbService.requireKb(tenantId, kbId);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private ChatDtos.SessionItem toItem(ChatSession s) {
        return new ChatDtos.SessionItem(
                s.getId(), s.getSessionUuid(), s.getTitle(), s.getKbId(),
                s.getProviderCode(), s.getScene(), s.getMessageCount(),
                s.getCreatedAt(), s.getUpdatedAt());
    }

    private ChatDtos.MessageItem toItem(ChatMessage m) {
        return new ChatDtos.MessageItem(
                m.getId(), m.getRole(), m.getContent(), m.getCitations(), m.getModel(),
                m.getPromptTokens(), m.getCompletionTokens(), m.getLatencyMs(),
                m.getTraceId(), m.getCreatedAt());
    }
}
