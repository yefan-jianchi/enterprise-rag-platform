package com.yit.rag.api;

import com.yit.rag.api.dto.chat.ChatDtos;
import com.yit.rag.common.R;
import com.yit.rag.common.tenant.TenantContext;
import com.yit.rag.service.chat.ChatService;
import com.yit.rag.service.chat.ChatSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * 对话接口。
 *
 * <p>这个类解决什么问题：会话管理 + 流式问答（SSE）。
 *
 * <p><b>SSE 的实现要点</b>（这是本模块技术含量最高的地方）：
 * <ol>
 *   <li>返回 {@link SseEmitter} 后 Spring MVC 会进入异步模式，
 *       HTTP 响应头立刻发出去（{@code Content-Type: text/event-stream}），
 *       前端马上进入「等待流」状态。</li>
 *   <li>真正的耗时逻辑交给 <b>独立的 sseExecutor 线程池</b>，
 *       绝不在 Tomcat 请求线程里跑 —— 否则并发几十个流就把请求线程占满，
 *       整个服务其他接口全部卡死。</li>
 *   <li>必须注册 {@code onTimeout / onCompletion / onError} 三个回调，
 *       把 emitter 的生命周期收干净（客户端关页面、网络断了都会触发）。</li>
 *   <li>捕获 {@link RejectedExecutionException}：线程池满了要给前端一个明确结论，
 *       不能静默失败。</li>
 * </ol>
 *
 * <p>为什么不用 WebFlux：整条链路的输入输出都是「一次请求 + 长连接推送」，
 * 真正耗时的模型推理在 Python AI 服务里。用 MVC 的 SseEmitter 完全可以胜任，
 * 而且代码是同步阻塞风格，团队里任何人都能看懂。
 * 引 WebFlux 会把整个技术栈染成响应式（JDBC 也得换 R2DBC），收益远小于成本。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Tag(name = "对话", description = "会话管理、流式问答（SSE）")
public class ChatController {

    private final ChatService chatService;
    private final ChatSessionService sessionService;

    @Qualifier("sseExecutor")
    private final Executor sseExecutor;

    /**
     * 会话列表。
     *
     * <p>{@code scene} 是<b>可选</b>参数：不传时返回当前用户的全部会话
     * （保持前端现有行为不变），传 {@code CS} / {@code CHAT} 时按场景过滤。
     */
    @GetMapping("/sessions")
    @PreAuthorize("hasAuthority('chat:history')")
    @Operation(summary = "会话列表", description = "scene 可选：不传返回全部，传 CS/CHAT 按场景过滤")
    public R<List<ChatDtos.SessionItem>> sessions(
            @RequestParam(value = "scene", required = false) String scene) {
        return R.ok(sessionService.list(scene));
    }

    /** 新建会话 */
    @PostMapping("/session")
    @PreAuthorize("hasAuthority('chat:ask')")
    @Operation(summary = "新建会话")
    public R<ChatDtos.SessionItem> createSession(@RequestBody ChatDtos.CreateSessionRequest request) {
        return R.ok(sessionService.create(request));
    }

    /** 某会话的历史消息 */
    @GetMapping("/messages")
    @PreAuthorize("hasAuthority('chat:history')")
    @Operation(summary = "会话消息列表")
    public R<List<ChatDtos.MessageItem>> messages(@RequestParam("sessionUuid") String sessionUuid) {
        return R.ok(sessionService.messages(sessionUuid));
    }

    /**
     * 流式问答（SSE）。
     *
     * <p>事件序列：{@code meta}（引用 + traceId）→ {@code delta}（正文增量，多次）→
     * {@code done}（结束，带 provider/model/elapsedMs）；异常时发 {@code error}。
     *
     * <p>{@code sessionUuid} 可以是前端自己生成的、库里还不存在的 UUID ——
     * 后端会自动建会话（见 ChatSessionService.requireOrCreate）。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('chat:ask')")
    @Operation(summary = "流式问答", description = "SSE：meta / delta / done / error 事件透传")
    public SseEmitter stream(@Valid @RequestBody ChatDtos.StreamRequest request) {
        SseEmitter emitter = chatService.createEmitter();
        registerCallbacks(emitter, "chat");

        // ⚠ 关键：TenantContext 是 ThreadLocal，而下面的逻辑要在 sseExecutor 的新线程里跑，
        // 新线程拿不到 ThreadLocal。所以必须在【当前请求线程】里把用户上下文取出来，
        // 显式传给异步任务，否则会在异步线程里抛「当前请求没有租户上下文」。
        TenantContext.CurrentUser currentUser = TenantContext.get();

        try {
            sseExecutor.execute(() -> runWithTenant(currentUser, () -> chatService.stream(emitter, request)));
        } catch (RejectedExecutionException e) {
            // 并发流太多：给前端一个明确结论，而不是让它一直转圈
            log.warn("SSE 线程池已满，拒绝请求: {}", e.getMessage());
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(java.util.Map.of("message", "当前并发问答过多，请稍后重试")));
            } catch (Exception ignored) {
                // 客户端可能已断开
            }
            emitter.complete();
        }
        return emitter;
    }

    /**
     * 在异步线程里重建租户上下文，执行完立刻清理。
     *
     * <p>为什么必须清理：sseExecutor 的线程是复用的，不清理就会把上一个请求的租户
     * 带到下一个请求上 —— 这是多租户系统里最严重的串租户事故来源。
     * 与 {@code JwtAuthenticationFilter} 里的清理逻辑是同一个道理。
     */
    static void runWithTenant(TenantContext.CurrentUser user, Runnable task) {
        try {
            if (user != null) {
                TenantContext.set(user);
            }
            task.run();
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 注册 emitter 生命周期回调。
     *
     * <p>这些回调看起来是「样板代码」，但少一个就会泄漏：客户端断开时线程不会被回收，
     * 也不会打日志，出问题时完全无从查起。
     */
    private void registerCallbacks(SseEmitter emitter, String tag) {
        emitter.onCompletion(() -> log.debug("[{}] SSE 流正常结束", tag));
        emitter.onTimeout(() -> {
            log.warn("[{}] SSE 流超时（客户端可能一直没读完），主动关闭", tag);
            emitter.complete();
        });
        emitter.onError(t -> log.info("[{}] SSE 流异常结束: {}", tag, t.getMessage()));
    }
}
