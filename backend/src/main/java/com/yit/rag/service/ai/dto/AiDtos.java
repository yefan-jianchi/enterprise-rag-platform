package com.yit.rag.service.ai.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * Python AI 服务的接口 DTO 集合。
 *
 * <p>这个类解决什么问题：AI 服务的 JSON 字段是 snake_case
 * （{@code file_path} / {@code chunk_count} / {@code elapsed_ms}），
 * 而 Java 侧习惯 camelCase。如果用同一个全局 ObjectMapper，
 * 就得给每个字段都写 {@code @JsonProperty}，非常啰嗦。
 *
 * <p>这里的做法：每个 DTO 单独加 {@code @JsonNaming(SNAKE_CASE)}，
 * 只影响这一个类，全局 Jackson 配置（GMT+8、yyyy-MM-dd HH:mm:ss）不受影响。
 * 一处声明、字段名全自动转换，新增字段时不会再漏写注解。
 *
 * <p>全部用 Java 21 record：DTO 是纯数据载体，不需要 setter，
 * record 天然不可变 + 自带 equals/hashCode/toString，也没有 Lombok 的样板代码。
 */
public final class AiDtos {

    private AiDtos() {
    }

    // ==================================================================
    //  健康检查
    // ==================================================================

    /** {@code GET /health} 的响应 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record HealthResponse(
            String status,
            String app,
            String version,
            String embedProvider,
            String llmProvider,
            boolean faissAvailable,
            String dataDir
    ) {
    }

    // ==================================================================
    //  文档解析
    // ==================================================================

    /**
     * {@code POST /api/v1/documents/parse} 的请求。
     *
     * <p>{@code docId} 是业务侧（kb_document.id）的 ID，AI 服务会原样透传回来，
     * 这样异步解析回来时能对上号。
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ParseRequest(
            String filePath,
            Long docId,
            Integer chunkSize,
            Integer chunkOverlap
    ) {
    }

    /** 解析出的单个分块 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ParsedChunk(
            Integer chunkIndex,
            String content,
            Integer tokenCount,
            Integer pageNo,
            Integer charStart,
            Integer charEnd
    ) {
    }

    /** {@code POST /api/v1/documents/parse} 的响应 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ParseResponse(
            Long docId,
            String fileName,
            String docType,
            Integer pageCount,
            Integer charCount,
            Integer chunkCount,
            List<ParsedChunk> chunks,
            Long elapsedMs
    ) {
    }

    // ==================================================================
    //  建索引
    // ==================================================================

    /** 建索引时传给 AI 服务的分块（chunkId 就是 kb_chunk.id，保证向量与业务数据一一对应） */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record IndexChunk(
            Long chunkId,
            Long docId,
            String docName,
            Integer chunkIndex,
            String content,
            Integer pageNo
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record IndexBuildRequest(
            Long kbId,
            List<IndexChunk> chunks,
            boolean rebuild
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record IndexBuildResponse(
            Long kbId,
            Integer indexedChunks,
            Integer totalChunks,
            Integer embedDim,
            String embedProvider,
            Long elapsedMs
    ) {
    }

    // ==================================================================
    //  检索
    // ==================================================================

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RetrieveRequest(
            Long kbId,
            String query,
            Integer topK,
            String strategy
    ) {
    }

    /** 检索命中的分块，score 是最终排序分（融合/重排之后） */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RetrievedChunk(
            Long chunkId,
            Long docId,
            String docName,
            Integer chunkIndex,
            String content,
            Integer pageNo,
            Double score,
            Double vectorScore,
            Double bm25Score,
            Double rrfScore,
            Double rerankScore,
            Integer rank
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RetrieveResponse(
            Long kbId,
            String query,
            List<String> rewrittenQueries,
            String hydeDocument,
            String strategy,
            Integer totalCandidates,
            List<RetrievedChunk> results,
            Long elapsedMs
    ) {
    }

    // ==================================================================
    //  RAG 问答
    // ==================================================================

    /**
     * {@code POST /api/v1/rag/ask} 与 {@code /api/v1/rag/stream} 的请求。
     *
     * <p>{@code systemPrompt} 在智能客服场景下会填入「工具调用结果」，
     * 让模型基于真实查询结果作答，而不是编造。
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RagAskRequest(
            Long kbId,
            String question,
            Integer topK,
            String strategy,
            String provider,
            String systemPrompt,
            Double temperature
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Citation(
            Integer index,
            Long chunkId,
            Long docId,
            String docName,
            Integer pageNo,
            Integer chunkIndex,
            Double score,
            String snippet
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RagAskResponse(
            String answer,
            List<Citation> citations,
            String provider,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            Long elapsedMs,
            String traceId
    ) {
    }

    // ==================================================================
    //  SSE 流式事件（注意：这里字段是 camelCase，与 rag_ask 的 snake_case 不同）
    // ==================================================================

    /**
     * {@code event: meta} 的内容。
     *
     * <p>刻意不加 SNAKE_CASE 注解：AI 服务在流式事件里用的就是 camelCase
     * （{@code traceId} / {@code citations}），与上面 ask 接口的 snake_case 风格不一致。
     * 这是真实项目里很常见的「接口风格漂移」，不能想当然地统一处理 ——
     * 必须照着实测报文来映射。
     */
    public record StreamMeta(String traceId, List<Citation> citations) {
    }

    /** {@code event: delta} 的内容：一段正文增量 */
    public record StreamDelta(String content) {
    }

    /** {@code event: done} 的内容 */
    public record StreamDone(Long elapsedMs, String provider, String model) {
    }

    /** {@code event: error} 的内容 */
    public record StreamError(String message) {
    }

    /** {@code POST /api/v1/index/delete-doc} 的响应 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DeleteDocResponse(Long kbId, Long docId, Integer removedVectors) {
    }
}
