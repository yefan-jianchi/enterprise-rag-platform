package com.yit.rag.service.chat;

import com.yit.rag.service.ai.AiServiceClient;
import com.yit.rag.service.ai.dto.AiDtos;
import com.yit.rag.service.ai.model.AiCallLogService;
import com.yit.rag.service.ai.model.ModelRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式问答的「模型调用 + Fallback」编排器。
 *
 * <p>这个类解决什么问题：流式场景下的自动 Fallback 有个特殊约束 ——
 * <b>只要已经往前端推过一个字节，就不能再换模型重试了</b>。
 * 因为前端会把两个模型的内容拼在一起，答案就废了。
 *
 * <p>所以这里用了一个「延迟转发」的技巧：
 * <pre>
 *   1. 先把 AI 服务的事件攒在缓冲区（不发给前端）
 *   2. 一旦收到第一个 delta，说明这次调用已经成功，立刻把缓冲区刷给前端，
 *      之后进入直通模式
 *   3. 如果整个调用都失败了（连不上、5xx），缓冲区直接丢掉，换下一个提供方重试
 * </pre>
 * 效果是：<b>连接阶段的失败对前端完全透明</b> —— 前端只会看到最终成功的那一次输出，
 * 而 ai_call_log 里如实记录了每一次失败尝试（这是 Fallback 可观测性的来源）。
 *
 * <p>与 {@code ModelRouter} 的分工：ModelRouter 负责「非流式」的 Fallback
 * （chatWithFallback，直接返回结果）；这里负责「流式」的 Fallback，
 * 因为流式的失败判定依赖「有没有发出过 delta」，是另一套逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatModelStreamService {

    private final AiServiceClient aiServiceClient;
    private final ModelRouter modelRouter;
    private final AiCallLogService callLogService;

    /**
     * 一次流式调用的上下文（日志归属与「是否已开始输出」的判定都在这里）。
     *
     * @param <T> 业务侧自己的结果类型（聊天是 StreamOutcome，客服是 CS 结果）
     */
    public interface Attempt<T> {
        /**
         * 用某个提供方执行一次调用。
         *
         * @param target  本次尝试使用的提供方
         * @param forward 事件转发器（内部处理缓冲与直通）
         */
        T run(ModelRouter.ProviderTarget target, Forwarder forward);
    }

    /**
     * 事件转发器：决定事件是「攒着」还是「直接发出去」。
     */
    public interface Forwarder {
        void meta(AiDtos.StreamMeta meta);

        void delta(String content);
    }

    /** 一次成功调用的产物（usedTarget 由调用方自己在 attempt 里捕获，这里不重复持有） */
    public record StreamOutcome(
            String traceId,
            List<AiDtos.Citation> citations,
            String answer,
            int deltaCount,
            AiDtos.StreamDone done,
            String errorMessage
    ) {
    }

    /**
     * 带 Fallback 的流式执行。
     *
     * @param preferredCode 期望的提供方编码，null 表示按优先级第一个可用的
     * @param callType      记账用的类型（CHAT / TOOL）
     * @param handler       业务侧的事件处理器（会收到去重后的事件，用于透传或累计）
     * @param attempt       实际发起调用的逻辑
     */
    public <T> T executeWithFallback(Long tenantId, Long userId, Long sessionId,
                                     String preferredCode, String callType,
                                     AiServiceClient.StreamHandler handler,
                                     Attempt<T> attempt) {
        List<ModelRouter.ProviderTarget> chain = modelRouter.resolveCallable(tenantId);
        if (preferredCode != null && !preferredCode.isBlank()) {
            chain = moveFirst(chain, preferredCode);
        }

        List<String> failures = new ArrayList<>();
        for (ModelRouter.ProviderTarget target : chain) {
            long start = System.currentTimeMillis();
            BufferedForwarder forwarder = new BufferedForwarder(handler);
            try {
                T result = attempt.run(target, forwarder);
                long latency = System.currentTimeMillis() - start;
                // 成功：记账（tokens 由业务侧从 done 事件里取，这里拿不到就记 0）
                if (!failures.isEmpty()) {
                    log.warn("流式 Fallback 生效：前 {} 次失败，最终由 {} 成功", failures.size(), target.describe());
                }
                return result;
            } catch (Exception e) {
                long latency = System.currentTimeMillis() - start;
                String reason = shortMessage(e);
                failures.add(target.describe() + " → " + reason);
                if (forwarder.hasEmitted()) {
                    // 已经推给前端了，不能再重试（否则内容会重复）
                    callLogService.recordFailure(tenantId, userId, sessionId, null, callType,
                            target.providerCode(), target.model(), latency, reason);
                    log.error("流式输出中途失败，无法重试（已输出 {} 段）: {}",
                            forwarder.emittedCount(), reason);
                    throw e instanceof RuntimeException re ? re : new RuntimeException(e);
                }
                // 尚未输出任何内容 → 安全重试下一个提供方
                callLogService.recordFailure(tenantId, userId, sessionId, null, callType,
                        target.providerCode(), target.model(), latency, reason);
                log.warn("流式调用失败（尚未输出，可安全切换）: {} 原因={}", target.describe(), reason);
            }
        }
        throw new com.yit.rag.common.exception.BizException(
                "所有模型提供方均调用失败：" + String.join(" | ", failures));
    }

    /** 记录一次成功的流式调用（由业务侧在拿到 done 后调用，因为 tokens 在 done 里） */
    public void recordSuccess(Long tenantId, Long userId, Long sessionId, String traceId,
                              String callType, ModelRouter.ProviderTarget target,
                              int promptTokens, int completionTokens, long latencyMs) {
        callLogService.recordSuccess(tenantId, userId, sessionId, traceId, callType,
                target.providerCode(), target.model(), promptTokens, completionTokens, latencyMs);
    }

    /**
     * 缓冲转发器：第一个 delta 到来之前先攒着，之后直通。
     *
     * <p>这是「流式 Fallback 不产生重复内容」的关键实现。
     */
    private static final class BufferedForwarder implements Forwarder {
        private final AiServiceClient.StreamHandler handler;
        private final List<Runnable> buffer = new ArrayList<>(4);
        private boolean flushed = false;
        private int emitted = 0;

        BufferedForwarder(AiServiceClient.StreamHandler handler) {
            this.handler = handler;
        }

        @Override
        public synchronized void meta(AiDtos.StreamMeta meta) {
            if (flushed) {
                handler.onMeta(meta);
            } else {
                buffer.add(() -> handler.onMeta(meta));
            }
        }

        @Override
        public synchronized void delta(String content) {
            if (!flushed) {
                // 第一个 delta：说明这次调用成功了，把之前攒的 meta 一起刷出去
                flushed = true;
                for (Runnable r : buffer) {
                    r.run();
                }
                buffer.clear();
            }
            emitted++;
            handler.onDelta(content);
        }

        synchronized boolean hasEmitted() {
            return emitted > 0 || flushed;
        }

        synchronized int emittedCount() {
            return emitted;
        }
    }

    /** 把指定编码的提供方挪到链首 */
    private List<ModelRouter.ProviderTarget> moveFirst(List<ModelRouter.ProviderTarget> chain, String code) {
        List<ModelRouter.ProviderTarget> head = new ArrayList<>();
        List<ModelRouter.ProviderTarget> tail = new ArrayList<>();
        for (ModelRouter.ProviderTarget t : chain) {
            if (t.providerCode().equalsIgnoreCase(code)) {
                head.add(t);
            } else {
                tail.add(t);
            }
        }
        head.addAll(tail);
        return head;
    }

    private static String shortMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        if (msg == null) {
            msg = t.getClass().getSimpleName();
        }
        return msg.length() <= 300 ? msg : msg.substring(0, 300) + "...";
    }
}
