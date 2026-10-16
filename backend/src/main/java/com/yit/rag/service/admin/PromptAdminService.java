package com.yit.rag.service.admin;

import com.yit.rag.api.dto.admin.AdminDtos;
import com.yit.rag.common.exception.BizException;
import com.yit.rag.common.tenant.TenantContext;
import com.yit.rag.domain.chat.AiPromptTemplate;
import com.yit.rag.repository.chat.AiPromptTemplateRepository;
import com.yit.rag.service.system.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Prompt 模板管理服务。
 *
 * <p>这个类解决什么问题：提示词是 RAG 效果的最大变量之一（「多加一句『不要编造』
 * 就能显著降低幻觉」），但改提示词如果需要发版，迭代速度会被开发排期绑死。
 * 所以把模板放到数据库，运营/算法同学改完立刻生效。
 *
 * <p>层级设计：{@code tenant_id = 0} 是平台级默认模板，租户可以覆盖。
 * 管理端列表把两层都返回（内置的置底），并标注 {@code tenantId} 让前端区分。
 *
 * <p>为什么不允许改 {@code templateCode}：它是代码里按名字取模板的 key
 * （{@code RAG_QA} / {@code CS_REPLY} / {@code REWRITE} / {@code HYDE} / {@code RERANK}），
 * 改了代码就找不到模板了。这是「配置」和「标识」的区别 —— 标识必须稳定。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptAdminService {

    private final AiPromptTemplateRepository templateRepository;
    private final AuditService auditService;

    /**
     * 模板列表（平台级 + 当前租户）。
     */
    @Transactional(readOnly = true)
    public List<AdminDtos.PromptItem> list() {
        Long tenantId = TenantContext.requireTenantId();
        List<AiPromptTemplate> templates = templateRepository.findVisible(tenantId);
        List<AdminDtos.PromptItem> items = new ArrayList<>(templates.size());
        for (AiPromptTemplate t : templates) {
            items.add(toItem(t));
        }
        log.debug("Prompt 模板列表: tenantId={} 共 {} 个", tenantId, items.size());
        return items;
    }

    /**
     * 更新模板内容 / 启停状态。
     *
     * <p>字段为 null 表示不改。{@code content} 传空字符串视为非法
     * （空模板会让模型收到空提示词，生成结果不可控），所以明确拒绝。
     */
    @Transactional
    public AdminDtos.PromptItem update(Long id, AdminDtos.UpdatePromptRequest req) {
        AiPromptTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("Prompt 模板不存在: " + id));

        List<String> changes = new ArrayList<>();
        if (req.content() != null) {
            if (req.content().isBlank()) {
                throw BizException.badRequest("模板内容不能为空");
            }
            template.setContent(req.content());
            changes.add("content(长度=" + req.content().length() + ")");
        }
        if (req.enabled() != null) {
            template.setEnabled(req.enabled() ? 1 : 0);
            changes.add("enabled=" + req.enabled());
        }
        if (changes.isEmpty()) {
            throw BizException.badRequest("没有需要更新的字段");
        }

        template = templateRepository.save(template);
        auditService.log(AuditService.ACTION_PROMPT_EDIT, "prompt:" + id,
                "更新 Prompt 模板 " + template.getTemplateCode() + " : " + String.join(", ", changes));

        log.info("Prompt 模板已更新: id={} code={} 变更={}", id, template.getTemplateCode(), changes);
        return toItem(template);
    }

    /**
     * 按模板码取内容（供 AI 服务/其他模块调用）。
     *
     * <p>优先级：租户自定义 &gt; 平台默认。取不到就返回 null，由调用方决定兜底策略。
     */
    @Transactional(readOnly = true)
    public String resolveContent(Long tenantId, String templateCode) {
        var tenantTpl = templateRepository.findByTenantIdAndTemplateCode(tenantId, templateCode);
        if (tenantTpl.isPresent() && isEnabled(tenantTpl.get())) {
            return tenantTpl.get().getContent();
        }
        var platformTpl = templateRepository.findByTenantIdAndTemplateCode(0L, templateCode);
        if (platformTpl.isPresent() && isEnabled(platformTpl.get())) {
            return platformTpl.get().getContent();
        }
        log.debug("未找到启用的 Prompt 模板: tenantId={} code={}", tenantId, templateCode);
        return null;
    }

    private boolean isEnabled(AiPromptTemplate t) {
        return t.getEnabled() != null && t.getEnabled() == 1;
    }

    private AdminDtos.PromptItem toItem(AiPromptTemplate t) {
        return new AdminDtos.PromptItem(
                t.getId(), t.getTenantId(), t.getTemplateCode(), t.getTemplateName(),
                t.getContent(), t.getVariables(),
                t.getEnabled() != null && t.getEnabled() == 1, t.getCreatedAt());
    }
}
