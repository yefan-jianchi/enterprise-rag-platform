package com.yit.rag.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yit.rag.common.exception.BizException;
import com.yit.rag.service.ai.dto.AiDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Python AI 服务的 Java 客户端。
 *
 * <p>这个类解决什么问题：后端与 AI 服务的职责边界是「后端管业务数据、AI 服务管重活」。
 * 这个类就是那条边界上的唯一通道 —— 所有对 AI 服务的 HTTP 调用都收在这里，
 * 好处是：超时策略、错误翻译、日志埋点只需要维护一份。
 *
 * <p>三件关键实现：
 * <ol>
 *   <li><b>健康探活</b>：{@code /health} 拿不到就说明 AI 服务没起来。
 *       调用方可以据此给出友好提示，而不是抛一个莫名其妙的连接异常。</li>
 *   <li><b>错误翻译</b>：HTTP 4xx/5xx 统一翻译成 {@link BizException}，
 *       并且把 AI 服务返回的 detail 带出来（FastAPI 的报错信息很有用，
 *       比如「chunks 不能为空」），不要让业务层看到 {@code HttpClientErrorException}。</li>
 *   <li><b>SSE 透传</b>：{@link #ragStream} 手工解析 {@code text/event-stream}，
 *       逐事件回调。为什么要手工解析？因为要把事件「原样透传给前端」，
 *       中途不能变成「攒完整个回答再返回」——那就不是流式了，
 *       首字延迟会从几百毫秒涨到十几秒，体验差别是量级上的。</li>
 * </ol>
 *
 * <p>契约来源：ai-service 已冻结，字段对应关系见 {@link AiDtos} 上的注释。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiServiceClient {

    private final @Qualifier("aiRestClient") RestClient restClient;
    private final ObjectMapper objectMapper;

    // ==================================================================
    //  健康检查
    // ==================================================================

    /**
     * 探活。返回 null 表示 AI 服务不可达（调用方决定怎么处理，不抛异常）。
     */
    public AiDtos.HealthResponse health() {
        try {
            return restClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(AiDtos.HealthResponse.class);
        } catch (Exception e) {
            log.warn("AI 服务探活失败: {}", e.getMessage());
            return null;
        }
    }

    /** AI 服务是否可用 */
    public boolean isHealthy() {
        AiDtos.HealthResponse h = health();
        return h != null && "ok".equalsIgnoreCase(h.status());
    }

    // ==================================================================
    //  文档解析
    // ==================================================================

    /**
     * 解析文档（按服务端路径）。
     *
     * <p>注意 {@code filePath} 必须是 AI 服务所在机器能读到的路径。
     * 本地部署时后端与 AI 服务同机，所以直接把本地存储的绝对路径传过去即可。
     */
    public AiDtos.ParseResponse parseDocument(String filePath, Long docId, Integer chunkSize, Integer chunkOverlap) {
        log.debug("调用 AI 解析文档: path={} docId={}", filePath, docId);
        try {
            AiDtos.ParseResponse resp = restClient.post()
                    .uri("/api/v1/documents/parse")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AiDtos.ParseRequest(filePath, docId, chunkSize, chunkOverlap))
                    .retrieve()
                    .body(AiDtos.ParseResponse.class);
            if (resp == null) {
                throw new BizException("AI 服务解析文档返回空结果");
            }
            log.debug("解析完成: docId={} 页数={} 字符数={} 分块数={} 耗时={}ms",
                    docId, resp.pageCount(), resp.charCount(), resp.chunkCount(), resp.elapsedMs());
            return resp;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("调用 AI 服务解析文档失败: " + rootMessage(e), e);
        }
    }

    // ==================================================================
    //  索引
    // ==================================================================

    /** 建 / 重建索引 */
    public AiDtos.IndexBuildResponse buildIndex(Long kbId, List<AiDtos.IndexChunk> chunks, boolean rebuild) {
        log.debug("调用 AI 建索引: kbId={} 分块数={} rebuild={}", kbId, chunks == null ? 0 : chunks.size(), rebuild);
        try {
            AiDtos.IndexBuildResponse resp = restClient.post()
                    .uri("/api/v1/index/build")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AiDtos.IndexBuildRequest(kbId, chunks, rebuild))
                    .retrieve()
                    .body(AiDtos.IndexBuildResponse.class);
            if (resp == null) {
                throw new BizException("AI 服务建索引返回空结果");
            }
            log.debug("建索引完成: kbId={} 本次={} 总计={} 维度={} provider={} 耗时={}ms",
                    kbId, resp.indexedChunks(), resp.totalChunks(), resp.embedDim(),
                    resp.embedProvider(), resp.elapsedMs());
            return resp;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("调用 AI 服务建索引失败: " + rootMessage(e), e);
        }
    }

    /** 删除某个文档在向量库里的全部向量 */
    public AiDtos.DeleteDocResponse deleteDocIndex(Long kbId, Long docId) {
        try {
            AiDtos.DeleteDocResponse resp = restClient.post()
                    .uri(uriBuilder -> uriBuilder.path("/api/v1/index/delete-doc")
                            .queryParam("kb_id", kbId)
                            .queryParam("doc_id", docId)
                            .build())
                    .retrieve()
                    .body(AiDtos.DeleteDocResponse.class);
            log.debug("删除向量完成: kbId={} docId={} removed={}", kbId, docId,
                    resp == null ? null : resp.removedVectors());
            return resp;
        } catch (Exception e) {
            // 删除向量失败不应该阻断业务侧删文档，记日志由调用方决定
            log.warn("删除文档向量失败 kbId={} docId={}: {}", kbId, docId, rootMessage(e));
            return null;
        }
    }

    // ==================================================================
    //  检索
    // ==================================================================

    /** 混合检索（VECTOR / BM25 / HYBRID_RRF / HYBRID_RERANK） */
    public AiDtos.RetrieveResponse retrieve(Long kbId, String query, Integer topK, String strategy) {
        try {
            AiDtos.RetrieveResponse resp = restClient.post()
                    .uri("/api/v1/retrieve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AiDtos.RetrieveRequest(kbId, query, topK, strategy))
                    .retrieve()
                    .body(AiDtos.RetrieveResponse.class);
            if (resp == null) {
                throw new BizException("AI 服务检索返回空结果");
            }
            return resp;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("调用 AI 服务检索失败: " + rootMessage(e), e);
        }
    }

    // ==================================================================
    //  一次性问答
    // ==================================================================

    public AiDtos.RagAskResponse ragAsk(AiDtos.RagAskRequest req) {
        try {
            AiDtos.RagAskResponse resp = restClient.post()
                    .uri("/api/v1/rag/ask")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(req)
                    .retrieve()
                    .body(AiDtos.RagAskResponse.class);
            if (resp == null) {
                throw new BizException("AI 服务问答返回空结果");
            }
            return resp;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("调用 AI 服务问答失败: " + rootMessage(e), e);
        }
    }

    // ==================================================================
    //  流式问答（SSE 透传的核心）
    // ==================================================================

    /**
     * 流式回调接口。
     *
     * <p>用回调而不是 {@code Flux}/{@code Stream}：因为业务侧要一边收一边往
     * 前端的 SseEmitter 里写，回调语义最直接，也不需要引入响应式依赖。
     */
    public interface StreamHandler {
        /** 收到 meta 事件（引用列表 + traceId） */
        void onMeta(AiDtos.StreamMeta meta);

        /** 收到一段正文增量 */
        void onDelta(String content);

        /** 收到 done 事件 */
        void onDone(AiDtos.StreamDone done);

        /** 收到 error 事件（AI 服务侧的模型异常） */
        void onError(String message);
    }

    /** 流式调用的结果汇总：业务侧落库时要用这些字段 */
    public record StreamResult(
            String traceId,
            List<AiDtos.Citation> citations,
            String answer,
            int deltaCount,
            AiDtos.StreamDone done,
            String errorMessage
    ) {
    }

    /**
     * 调用 AI 服务的 {@code /api/v1/rag/stream}，逐事件回调，并汇总结果。
     *
     * <p>为什么用「手工解析 SSE」而不是让 Spring 解码：AI 服务返回的事件格式是
     * {@code event: xxx} + {@code data: {...}} 两行一组，Spring 没有现成的同步解析器
     * （响应式有，但 MVC 项目不想引 WebFlux）。这个格式非常简单稳定，手工解析 30 行就够，
     * 而且能完全控制「原样透传」的语义。
     *
     * <p>异常语义很重要：<b>连接阶段</b>的失败（连不上、5xx）会抛出异常，
     * 让 {@code ModelRouter} 有机会切到下一个模型提供方；
     * 而<b>已经开始推送</b>之后的失败只会记录在结果里 —— 因为此时前端已经收到部分内容，
     * 再换模型重来会导致内容重复，只能如实报错收尾。
     */
    public StreamResult ragStream(AiDtos.RagAskRequest req, StreamHandler handler) {
        StringBuilder answerBuf = new StringBuilder();
        List<AiDtos.Citation> citations = new ArrayList<>();
        String[] traceId = {null};
        AiDtos.StreamDone[] done = {null};
        String[] error = {null};
        int[] deltaCount = {0};

        try {
            restClient.post()
                    .uri("/api/v1/rag/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(req)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            // 连接阶段的错误 → 抛出去，让 ModelRouter 做 Fallback
                            throw new BizException("AI 服务流式接口返回 " + response.getStatusCode());
                        }
                        try (InputStream in = response.getBody();
                             BufferedReader reader = new BufferedReader(
                                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
                            // parseSse 声明了 throws Exception（读写 + JSON 解析），
                            // 而 exchange 的回调不允许抛受检异常，所以在这里统一转成 BizException
                            parseSse(reader, handler, answerBuf, citations, traceId, done, error, deltaCount);
                        } catch (Exception ex) {
                            throw new BizException("读取 AI 服务流式响应失败: " + ex.getMessage(), ex);
                        }
                        return null;
                    });
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("调用 AI 服务流式接口失败: " + rootMessage(e), e);
        }

        return new StreamResult(traceId[0], citations, answerBuf.toString(),
                deltaCount[0], done[0], error[0]);
    }

    /**
     * 逐行解析 SSE 流。
     *
     * <p>SSE 规范：以空行分隔事件块；每个块里 {@code event:} 是事件名、
     * {@code data:} 是负载（可能多行，这里 AI 服务只会给一行）。
     */
    private void parseSse(BufferedReader reader, StreamHandler handler,
                          StringBuilder answerBuf, List<AiDtos.Citation> citations,
                          String[] traceId, AiDtos.StreamDone[] done, String[] error,
                          int[] deltaCount) throws Exception {
        String currentEvent = null;
        StringBuilder dataBuf = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                // 一个事件块结束，分发
                if (currentEvent != null && !dataBuf.isEmpty()) {
                    dispatch(currentEvent, dataBuf.toString(), handler, answerBuf,
                            citations, traceId, done, error, deltaCount);
                }
                currentEvent = null;
                dataBuf.setLength(0);
                continue;
            }
            if (line.startsWith("event:")) {
                currentEvent = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                if (!dataBuf.isEmpty()) {
                    dataBuf.append('\n');
                }
                dataBuf.append(line.substring("data:".length()).trim());
            }
            // 忽略 : 开头的注释行、id: / retry: 等字段（AI 服务不会用到）
        }
        // 流末尾可能没有空行，补一次分发
        if (currentEvent != null && !dataBuf.isEmpty()) {
            dispatch(currentEvent, dataBuf.toString(), handler, answerBuf,
                    citations, traceId, done, error, deltaCount);
        }
    }

    /** 按事件类型分发并累计 */
    private void dispatch(String event, String data, StreamHandler handler,
                          StringBuilder answerBuf, List<AiDtos.Citation> citations,
                          String[] traceId, AiDtos.StreamDone[] done, String[] error,
                          int[] deltaCount) {
        try {
            switch (event) {
                case "meta" -> {
                    AiDtos.StreamMeta meta = objectMapper.readValue(data, AiDtos.StreamMeta.class);
                    traceId[0] = meta.traceId();
                    if (meta.citations() != null) {
                        citations.addAll(meta.citations());
                    }
                    handler.onMeta(meta);
                }
                case "delta" -> {
                    AiDtos.StreamDelta delta = objectMapper.readValue(data, AiDtos.StreamDelta.class);
                    String content = delta.content() == null ? "" : delta.content();
                    answerBuf.append(content);
                    deltaCount[0]++;
                    handler.onDelta(content);
                }
                case "done" -> {
                    AiDtos.StreamDone d = objectMapper.readValue(data, AiDtos.StreamDone.class);
                    done[0] = d;
                    handler.onDone(d);
                }
                case "error" -> {
                    AiDtos.StreamError err = objectMapper.readValue(data, AiDtos.StreamError.class);
                    error[0] = err.message();
                    handler.onError(err.message());
                }
                default -> log.debug("忽略未知的 SSE 事件: {}", event);
            }
        } catch (Exception e) {
            // 单个事件解析失败不应该中断整条流：记日志继续
            log.warn("SSE 事件解析失败 event={} data={}: {}", event, abbreviate(data), e.getMessage());
        }
    }

    // ==================================================================
    //  工具方法
    // ==================================================================

    /** 把异常链里最内层的信息取出来，FastAPI 的 detail 才能露出来 */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
