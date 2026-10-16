package com.yit.rag.domain.system;

import com.yit.rag.domain.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicInsert;

/**
 * 权限点（对应 {@code sys_permission}）。
 *
 * <p>这个类解决什么问题：把「能不能做某件事」定义成稳定的字符串码，
 * 而不是散落在代码里的 if-else。权限码形如 {@code kb:doc:upload}：
 * <pre>模块:资源:动作</pre>
 * 前端按 module 分组渲染菜单，后端用 {@code @PreAuthorize("hasAuthority('...')")} 校验。
 */
@Getter
@Setter
@Entity
@DynamicInsert
@Table(name = "sys_permission")
public class SysPermission extends CreatedEntity {

    /** 权限码，如 kb:doc:upload（唯一） */
    @Column(name = "perm_code", nullable = false, length = 96)
    private String permCode;

    @Column(name = "perm_name", nullable = false, length = 96)
    private String permName;

    /** 所属模块：kb / chat / cs / model / audit / system */
    @Column(name = "module", nullable = false, length = 48)
    private String module;
}
