package com.yit.rag.api;

import com.yit.rag.api.dto.admin.AdminDtos;
import com.yit.rag.common.R;
import com.yit.rag.service.admin.CostReportService;
import com.yit.rag.service.admin.ModelAdminService;
import com.yit.rag.service.admin.PromptAdminService;
import com.yit.rag.service.system.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端接口：模型配置 / 审计日志 / 成本统计 / Prompt 模板。
 *
 * <p>这个类解决什么问题：企业级系统必须让管理员「看得见、管得动」——
 * 看到模型配置与 Key 状态、看到谁做了什么操作、看到模型花了多少钱、能改提示词。
 *
 * <p>权限设计：每个接口都声明了独立的权限码，而不是笼统的「ADMIN 才能访问」。
 * 好处是可以做出更细的角色（例如给「财务」角色只开 {@code cost:view}，
 * 给「算法」角色开 {@code model:view} + {@code prompt:manage}），
 * 不用改一行代码。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "管理端", description = "模型配置、审计日志、成本统计、Prompt 模板")
public class AdminController {

    private final ModelAdminService modelAdminService;
    private final CostReportService costReportService;
    private final PromptAdminService promptAdminService;
    private final AuditService auditService;

    // ==================================================================
    //  模型配置
    // ==================================================================

    /** 模型提供方列表（Key 脱敏，只暴露「配了没有」与来源） */
    @GetMapping("/models")
    @PreAuthorize("hasAuthority('model:view')")
    @Operation(summary = "模型提供方列表", description = "API Key 脱敏返回，只暴露是否已配置与来源")
    public R<List<AdminDtos.ModelItem>> models() {
        return R.ok(modelAdminService.list());
    }

    /** 更新模型配置（启停、优先级、模型名、API Key） */
    @PutMapping("/models/{id}")
    @PreAuthorize("hasAuthority('model:edit')")
    @Operation(summary = "更新模型配置", description = "字段可选：enabled / priority / chatModel / apiKey")
    public R<AdminDtos.ModelItem> updateModel(@PathVariable("id") Long id,
                                              @RequestBody AdminDtos.UpdateModelRequest request) {
        return R.ok(modelAdminService.update(id, request));
    }

    // ==================================================================
    //  审计日志
    // ==================================================================

    /**
     * 审计日志分页查询。
     *
     * <p>契约：{@code {total, records:[...]}}，page 从 0 开始，size 默认 20（上限 200）。
     */
    @GetMapping("/audit")
    @PreAuthorize("hasAuthority('audit:view')")
    @Operation(summary = "审计日志", description = "返回 {total, page, size, records}")
    public R<Map<String, Object>> audit(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return R.ok(auditService.page(page, size));
    }

    // ==================================================================
    //  成本统计
    // ==================================================================

    /**
     * 成本报表。
     *
     * <p>契约：{@code {totalTokens,totalCost,byDay:[{date,tokens,cost}],byModel:[{model,tokens,cost,calls}]}}；
     * 这里额外多返回 {@code totalCalls / days / byProvider} 三个字段（前端可以忽略，
     * 但对排障和演示非常有用：byProvider 能直接看出 Fallback 之后各家的实际承担量与失败次数）。
     */
    @GetMapping("/cost")
    @PreAuthorize("hasAuthority('cost:view')")
    @Operation(summary = "成本统计", description = "按天/按模型/按提供方聚合 token 与费用")
    public R<AdminDtos.CostReport> cost(
            @RequestParam(value = "days", defaultValue = "7") Integer days) {
        return R.ok(costReportService.report(days));
    }

    // ==================================================================
    //  Prompt 模板
    // ==================================================================

    /** Prompt 模板列表（平台级 + 当前租户） */
    @GetMapping("/prompts")
    @PreAuthorize("hasAuthority('prompt:manage')")
    @Operation(summary = "Prompt 模板列表")
    public R<List<AdminDtos.PromptItem>> prompts() {
        return R.ok(promptAdminService.list());
    }

    /** 更新 Prompt 模板内容或启停状态 */
    @PutMapping("/prompts/{id}")
    @PreAuthorize("hasAuthority('prompt:manage')")
    @Operation(summary = "更新 Prompt 模板", description = "字段可选：content / enabled")
    public R<AdminDtos.PromptItem> updatePrompt(@PathVariable("id") Long id,
                                                @RequestBody AdminDtos.UpdatePromptRequest request) {
        return R.ok(promptAdminService.update(id, request));
    }
}
