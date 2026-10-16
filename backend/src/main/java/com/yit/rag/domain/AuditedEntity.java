package com.yit.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 带 created_at + updated_at 的实体基类。
 *
 * <p>这个类解决什么问题：知识库、文档、会话这些「会被反复修改」的表都要 updated_at。
 * 同样交给 MySQL 的 {@code ON UPDATE CURRENT_TIMESTAMP} 维护，
 * 应用侧只读，避免 Hibernate 与数据库两边都写时间导致的不一致。
 */
@Getter
@Setter
@MappedSuperclass
public abstract class AuditedEntity extends CreatedEntity {

    /** 更新时间，由数据库自动维护（只读） */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
