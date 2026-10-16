package com.yit.rag.service.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 事件写入器 —— 把 Agent / AI 服务的输出原样推给前端。
 *
 * <p>这个类解决什么问题：SSE 接口散落着三件容易写错的琐事，集中封装起来：
 * <ol>
 *   <li><b>事件名 + JSON 数据的组装</b>：{@code event: xxx\ndata: {...}\n\n}。
 *       格式错一个换行，前端就收不到事件（而且不会有任何报错，极难排查）。</li>
 *   <li><b>断连容错</b>：用户在流式输出中途关掉页面，写入会抛
 *       {@code IOException}（Broken pipe）。这不是错误，不应该打 ERROR 堆栈把日志刷满。</li>
 *   <li><b>幂等收尾</b>：{@link #complete()} 可能被多条路径调用（正常结束、异常兜底、超时），
 *       用 CAS 保证只执行一次，避免 {@code complete()} 被调两次导致的 IllegalStateException。</li>
 * </ol>
 *
 * <p>事件协议（与 AI 服务、前端三方约定，不要改）：
 * <pre>
 *   event: meta    data: {"traceId":"...","citations":[...]}
 *   event: delta   data: {"content":"增量文本"}
 *   event: done    data: {"elapsedMs":123,"provider":"mock","model":"mock-chat"}
 *   event: error   data: {"message":"..."}
 * </pre>
 */
@Slf4j
public class SseWriter {

    private final SseEmitter emitter;
    private final AtomicBoolean finished = new AtomicBoolean(false);

    public SseWriter(SseEmitter emitter) {
        this.emitter = emitter;
    }

    /** 发送 meta 事件（引用列表 + traceId） */
    public void meta(Object payload) {
        send("meta", payload);
    }

    /** 发送一段正文增量 */
    public void delta(String content) {
        send("delta", java.util.Map.of("content", content == null ? "" : content));
    }

    /** 发送 done 事件 */
    public void done(Object payload) {
        send("done", payload);
    }

    /** 发送 error 事件 */
    public void error(String message) {
        send("error", java.util.Map.of("message", message == null ? "未知错误" : message));
    }

    /**
     * 发送任意命名事件。
     *
     * @return 是否发送成功（客户端已断连时返回 false）
     */
    public boolean send(String eventName, Object payload) {
        if (finished.get()) {
            log.debug("SSE 流已结束，忽略事件 {}", eventName);
            return false;
        }
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
            return true;
        } catch (IOException | IllegalStateException e) {
            // 客户端断开（关页面、切路由）是正常现象：标记结束，不再重复写
            log.info("SSE 客户端已断开，停止推送 event={}: {}", eventName, e.getMessage());
            finished.set(true);
            return false;
        } catch (Exception e) {
            log.warn("SSE 发送失败 event={}: {}", eventName, e.getMessage());
            return false;
        }
    }

    /** 正常结束流（幂等） */
    public void complete() {
        if (finished.compareAndSet(false, true)) {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.debug("SSE complete 异常（通常无害）: {}", e.getMessage());
            }
        }
    }

    /** 异常结束流（幂等）：先告诉前端原因，再关闭 */
    public void completeWithError(String message) {
        if (finished.compareAndSet(false, true)) {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(java.util.Map.of("message", message == null ? "服务异常" : message)));
            } catch (Exception ignored) {
                // 客户端可能已经走了，忽略
            }
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 同上
            }
        }
    }

    /** 流是否已经结束 */
    public boolean isFinished() {
        return finished.get();
    }
}
