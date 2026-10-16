package com.yit.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 带 created_at 的实体基类。
 *
 * <p>这个类解决什么问题：19 张业务表里绝大多数都有 {@code id} 与 {@code created_at}，
 * 抽到基类里避免每张表重复写一遍主键映射；同时统一 {@code GenerationType.IDENTITY}
 * （对齐 MySQL AUTO_INCREMENT），不要用序列/UUID 那套。
 *
 * <p>注意 {@code created_at} 标了 {@code insertable = false, updatable = false}：
 * 时间戳由 MySQL 自己填（DEFAULT CURRENT_TIMESTAMP），
 * 这样「数据库时间」与「应用时间」不会打架，也避免时区换算差异。
 */
@Getter
@Setter
@MappedSuperclass
public abstract class CreatedEntity implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 创建时间，由数据库默认值填充（只读） */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
