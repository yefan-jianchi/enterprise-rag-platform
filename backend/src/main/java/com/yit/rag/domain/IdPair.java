package com.yit.rag.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 通用「双 ID 复合主键」。
 *
 * <p>这个类解决什么问题：{@code sys_user_role} 与 {@code sys_role_permission}
 * 都是纯关联表，主键是 (左ID, 右ID)，没有自增列也没有额外字段。
 * 用一个 {@code @IdClass} 去表达即可，不必为这两张表各自造一个 Key 类，
 * 也不必把它们硬掰成 {@code @ManyToMany}（那样会引入懒加载与级联的复杂度）。
 *
 * <p>作为 JPA 的 IdClass 必须：实现 {@link Serializable}、有无参构造、正确实现 equals/hashCode。
 * 这里用 Lombok 的 {@code @Data} 保证。
 *
 * <p>注意字段名必须与被标注实体的主键属性名完全一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IdPair implements Serializable {
    private Long leftId;
    private Long rightId;
}
