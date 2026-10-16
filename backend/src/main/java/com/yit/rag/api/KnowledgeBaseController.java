package com.yit.rag.api;

import com.yit.rag.api.dto.kb.KbDtos;
import com.yit.rag.common.R;
import com.yit.rag.service.kb.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库接口。
 *
 * <p>这个类解决什么问题：对外暴露知识库的增删查。Controller 层刻意保持极薄 ——
 * 只做「参数绑定 + 权限声明 + 调 Service」，不写任何业务逻辑。
 *
 * <p>权限控制用 {@code @PreAuthorize} 声明在方法上，权限码与 {@code sys_permission}
 * 表里的数据一一对应。这样做的价值：接口权限是「数据驱动」的，
 * 给某个角色加权限只需要改 sys_role_permission，不用改代码。
 */
@Slf4j
@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
@Tag(name = "知识库", description = "知识库的增删查")
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;

    /** 知识库列表（含文档数、分块数统计） */
    @GetMapping("/list")
    @PreAuthorize("hasAuthority('kb:view')")
    @Operation(summary = "知识库列表")
    public R<List<KbDtos.KbListItem>> list() {
        return R.ok(kbService.list());
    }

    /** 新建知识库 */
    @PostMapping
    @PreAuthorize("hasAuthority('kb:create')")
    @Operation(summary = "新建知识库")
    public R<KbDtos.KbListItem> create(@RequestBody KbDtos.CreateKbRequest request) {
        return R.ok(kbService.create(request));
    }

    /** 删除知识库（连带文档、分块与向量索引） */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('kb:delete')")
    @Operation(summary = "删除知识库")
    public R<Void> delete(@PathVariable("id") Long id) {
        kbService.delete(id);
        return R.ok();
    }
}
