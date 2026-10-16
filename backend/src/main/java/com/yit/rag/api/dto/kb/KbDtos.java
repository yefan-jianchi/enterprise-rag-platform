package com.yit.rag.api.dto.kb;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 知识库相关的接口出入参（严格对齐前端契约）。
 *
 * <p>关于时间格式：{@code java.time.LocalDateTime} 默认会被 Jackson 序列化成
 * ISO-8601（{@code 2026-10-16T19:45:06}），而前端契约要求 {@code yyyy-MM-dd HH:mm:ss}。
 * Spring Boot 的 {@code spring.jackson.date-format} <b>只对 java.util.Date 生效</b>，
 * 对 JSR-310 类型不起作用，所以必须在每个时间字段上显式声明
 * {@link JsonFormat}。这是个很容易踩的坑：全局配了以为生效了，实际返回的还是带 T 的格式。
 */
public final class KbDtos {

    /** 统一的时间格式（与 spring.jackson.date-format 保持一致） */
    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private KbDtos() {
    }

    /**
     * 知识库列表项 —— 字段与前端约定的完全一致：
     * {@code {id,kbName,description,embedModel,chunkSize,topK,docCount,chunkCount,createdAt}}
     *
     * <p>注意 {@code docCount / chunkCount} 是「统计值」而不是表里的字段，
     * 需要额外查一次聚合。这里在 Service 层一次性算好，
     * 避免前端为了显示两个数字再发两次请求。
     */
    public record KbListItem(
            Long id,
            String kbName,
            String description,
            String embedModel,
            Integer chunkSize,
            Integer chunkOverlap,
            Integer topK,
            Double scoreThreshold,
            Long docCount,
            Long chunkCount,
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
            LocalDateTime createdAt
    ) {
    }

    /** 新建知识库请求 */
    public record CreateKbRequest(
            String kbName,
            String description,
            String embedModel,
            Integer chunkSize,
            Integer chunkOverlap,
            Integer topK
    ) {
    }

    /** 文档列表项 */
    public record DocListItem(
            Long id,
            Long kbId,
            String docName,
            String docType,
            Long fileSize,
            String status,
            Integer chunkCount,
            String errorMsg,
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")            LocalDateTime parsedAt,
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")            LocalDateTime indexedAt,
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
            LocalDateTime createdAt
    ) {
    }

    /** 分块列表项（前端「查看分块」用） */
    public record ChunkItem(
            Long id,
            Integer chunkIndex,
            String content,
            Integer tokenCount,
            Integer pageNo,
            Integer charStart,
            Integer charEnd
    ) {
    }
}
