package com.yit.rag.service.admin;

import com.yit.rag.api.dto.admin.AdminDtos;
import com.yit.rag.common.exception.BizException;
import com.yit.rag.common.tenant.TenantContext;
import com.yit.rag.common.util.CryptoUtil;
import com.yit.rag.domain.ai.AiModelProvider;
import com.yit.rag.repository.ai.AiModelProviderRepository;
import com.yit.rag.service.ai.model.ModelRouter;
import com.yit.rag.service.cache.CacheService;
import com.yit.rag.service.system.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型配置管理服务。
 *
 * <p>这个类解决什么问题：多模型适配层的「控制台」。
 * 运维/管理员需要在这里看到「有哪几家模型、优先级怎么排、Key 配了没有」，
 * 并且能不重启就调整（改优先级、启停某家、换模型名、换 Key）。
 *
 * <p>三个必须讲清楚的设计点：
 * <ol>
 *   <li><b>Key 永不回显</b>：列表只返回脱敏值（{@code sk-1****abcd}）+ 是否已配置 + 来源。
 *       管理员需要判断「配错了没有」，但把 Key 返给浏览器就等于泄露
 *       （XSS、代理日志、浏览器插件都在盯着它）。</li>
 *   <li><b>Key 存储加密</b>：写库前用 AES-256-GCM 加密（{@link CryptoUtil}），
 *       库里只有密文。即使数据库被拖走也拿不到 Key。</li>
 *   <li><b>改完就生效 + 清缓存</b>：模型配置是高频读取的数据（每次对话都要查），
 *       所以会缓存；更新时必须清掉缓存，否则「改了优先级但没生效」这种诡异问题很难查。</li>
 * </ol>
 *
 * <p>Key 的读取优先级与 {@link ModelRouter#resolveApiKey} 保持一致：
 * 环境变量 &gt; 数据库密文。这样容器化部署（Secret 注入）与手工配置两种方式都能用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelAdminService {

    /** 模型配置缓存 key 前缀（更新时要按这个前缀清） */
    private static final String CACHE_PREFIX = "admin:models:";

    private final AiModelProviderRepository providerRepository;
    private final ModelRouter modelRouter;
    private final CryptoUtil cryptoUtil;
    private final CacheService cacheService;
    private final AuditService auditService;

    /**
     * 模型提供方列表（平台级 + 当前租户）。
     */
    @Transactional(readOnly = true)
    public List<AdminDtos.ModelItem> list() {
        Long tenantId = TenantContext.requireTenantId();
        List<AiModelProvider> providers = providerRepository.findAllVisible(tenantId);
        List<AdminDtos.ModelItem> items = new ArrayList<>(providers.size());
        for (AiModelProvider p : providers) {
            items.add(toItem(p));
        }
        log.debug("模型配置列表: tenantId={} 共 {} 个", tenantId, items.size());
        return items;
    }

    /**
     * 更新模型配置。
     *
     * <p>每个字段都是「传了才改」，避免前端只改优先级却把模型名清空的事故。
     */
    @Transactional
    public AdminDtos.ModelItem update(Long id, AdminDtos.UpdateModelRequest req) {
        AiModelProvider provider = providerRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("模型提供方不存在: " + id));

        List<String> changes = new ArrayList<>();

        if (req.enabled() != null) {
            provider.setEnabled(req.enabled() ? 1 : 0);
            changes.add("enabled=" + req.enabled());
        }
        if (req.priority() != null) {
            if (req.priority() < 0) {
                throw BizException.badRequest("优先级不能为负数（数值越小越优先）");
            }
            provider.setPriority(req.priority());
            changes.add("priority=" + req.priority());
        }
        if (req.chatModel() != null && !req.chatModel().isBlank()) {
            provider.setChatModel(req.chatModel().trim());
            changes.add("chatModel=" + req.chatModel().trim());
        }
        if (req.apiKey() != null) {
            if (req.apiKey().isBlank()) {
                // 显式清空：把 Key 从库里删掉（例如要切回用环境变量）
                provider.setApiKeyCipher("");
                changes.add("apiKey=已清空");
            } else {
                // 加密后落库，绝不存明文
                provider.setApiKeyCipher(cryptoUtil.encrypt(req.apiKey().trim()));
                changes.add("apiKey=已更新（" + cryptoUtil.mask(req.apiKey().trim()) + "）");
            }
        }

        provider = providerRepository.save(provider);

        // 清缓存：让下次读取立刻拿到新配置，不用等 TTL 过期
        long evicted = cacheService.evictByPrefix(CACHE_PREFIX);
        if (evicted > 0) {
            log.debug("模型配置变更，清理了 {} 个缓存 key", evicted);
        }

        auditService.log(AuditService.ACTION_MODEL_EDIT, "model:" + id,
                "更新模型配置 " + provider.getProviderCode() + " : " + String.join(", ", changes));

        log.info("模型配置已更新: id={} provider={} 变更={}",
                id, provider.getProviderCode(), changes);
        return toItem(provider);
    }

    // ==================================================================
    //  内部
    // ==================================================================

    /**
     * 把实体转成对外的 DTO（含 Key 脱敏与来源判断）。
     */
    private AdminDtos.ModelItem toItem(AiModelProvider p) {
        // Key 来源判断直接复用 ModelRouter，保证与真实调用链的口径完全一致
        // （否则会出现「管理端显示已配置、实际调用却说没配」这种最难排查的问题）
        String source = modelRouter.resolveKeySource(p);
        String masked = "none".equals(source) ? "" : cryptoUtil.mask(modelRouter.resolveApiKey(p));

        boolean enabled = p.getEnabled() != null && p.getEnabled() == 1;
        // callable 同样复用 ModelRouter 的判定
        boolean callable = enabled && modelRouter.isCallable(p);
        boolean configured = !"none".equals(source);

        return new AdminDtos.ModelItem(
                p.getId(), p.getProviderCode(), p.getProviderName(), p.getBaseUrl(),
                p.getChatModel(), p.getEmbedModel(), p.getEmbedDim(),
                p.getPriority(), enabled,
                configured, masked, source, callable,
                p.getExtraJson(), p.getCreatedAt());
    }

    /** 供 Controller 预热缓存使用（可选优化） */
    public void warmUp(Long tenantId) {
        try {
            cacheService.put(CACHE_PREFIX + tenantId, list(), Duration.ofMinutes(10));
        } catch (Exception e) {
            log.debug("模型配置预热失败（无害）: {}", e.getMessage());
        }
    }
}
