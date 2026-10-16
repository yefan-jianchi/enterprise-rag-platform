package com.yit.rag.service.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yit.rag.common.exception.BizException;
import com.yit.rag.config.AppProperties;
import com.yit.rag.domain.ai.AiCallLog;
import com.yit.rag.domain.ai.AiModelProvider;
import com.yit.rag.repository.ai.AiModelProviderRepository;
import com.yit.rag.common.util.CryptoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 模型路由器 —— 多模型适配 + 自动 Fallback 的核心。
 *
 * <p>这个类解决什么问题：线上大模型 API 的不确定性很高 ——
 * 限流、超时、余额不足、服务抖动。如果只接一家，这些情况就直接变成用户侧的报错。
 * 这个类把「按优先级挑可用模型、失败自动切下一个、每次调用都记账」收敛到一处，
 * 业务层只写一句 {@code modelRouter.chat(...)}。
 *
 * <p>工作流程：
 * <pre>
 *   1. 从 ai_model_provider 取 enabled=1 的提供方，按 priority 升序
 *      （种子数据：deepseek=10 → dashscope=20 → openai=30(停用) → mock=99）
 *   2. 逐个判断「可用性」：mock 不需要 Key；其余必须有 Key
 *      （Key 来源：环境变量 DEEPSEEK_API_KEY 优先，其次库里的 api_key_cipher 解密）
 *   3. 按顺序尝试调用：
 *        成功 → 写 ai_call_log(success=1) 并返回
 *        失败 → 写 ai_call_log(success=0)，继续下一个
 *   4. 全部失败 → 抛 BizException，把每一次的失败原因聚合起来返回
 * </pre>
 *
 * <p>为什么「可用性判断」这么重要：本机演示时数据库里 deepseek/dashscope 的
 * api_key_cipher 是空的。如果不做判断，每次提问都会先去连 DeepSeek（超时/401 很快失败），
 * 白白增加几秒延迟才落到 mock。先判断 Key 是否配置，就能直接命中 mock，体验完全不同。
 *
 * <p>命名环境变量的规则：{@code <PROVIDER_CODE 大写>_API_KEY}，
 * 平台级配置还可以用 {@code APP_AI_<CODE>_API_KEY} 覆盖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelRouter {

    private final AiModelProviderRepository providerRepository;
    private final AiCallLogService callLogService;
    private final CryptoUtil cryptoUtil;
    private final ObjectMapper objectMapper;
    private final AppProperties props;

    private final @Qualifier("aiRestClient") RestClient restClient;

    /** Spring AI 引擎的模型工厂（仅在 app.ai.engine=spring-ai 时被使用） */
    private final SpringAiChatModelFactory springAiChatModelFactory;

    /**
     * 一个「已解析完毕、可以直接调用」的提供方目标。
     *
     * <p>把解密、环境变量取值、可用性判断都提前做完，后面的调用逻辑就不用再关心这些。
     */
    public record ProviderTarget(
            AiModelProvider entity,
            String providerCode,
            String baseUrl,
            String apiKey,
            String model,
            boolean mock
    ) {
        /** Key 是否已配置（mock 不需要） */
        public boolean callable() {
            return mock || (apiKey != null && !apiKey.isBlank());
        }

        public String describe() {
            return providerCode + "/" + model;
        }
    }

    /**
     * 只用来记日志的轻量目标（流式场景下真正的 target 在 attempt 内部，外层拿不到）。
     *
     * <p>不要用它发起调用 —— 它没有 baseUrl 和 apiKey。
     */
    public static ProviderTarget loggingTarget(String providerCode, String model) {
        return new ProviderTarget(null,
                providerCode == null ? "unknown" : providerCode,
                null, null,
                model == null ? "unknown" : model,
                false);
    }

    // ==================================================================
    //  提供方解析
    // ==================================================================

    /**
     * 取当前租户可用的提供方列表（按 priority 升序）。
     *
     * <p>注意返回值包含「Key 未配置」的项：管理端需要看到全部配置，
     * 而调用链需要用 {@link ProviderTarget#callable()} 再过滤一次。
     */
    public List<ProviderTarget> resolveAll(Long tenantId) {
        List<AiModelProvider> entities = providerRepository.findAvailable(tenantId);
        List<ProviderTarget> targets = new ArrayList<>(entities.size());
        for (AiModelProvider e : entities) {
            targets.add(toTarget(e));
        }
        return targets;
    }

    /**
     * 取「真正可以被调用」的提供方，按优先级升序 —— Fallback 链就是这么来的。
     */
    public List<ProviderTarget> resolveCallable(Long tenantId) {
        List<ProviderTarget> all = resolveAll(tenantId);
        List<ProviderTarget> callable = all.stream().filter(ProviderTarget::callable).toList();
        if (callable.isEmpty()) {
            throw new BizException("没有可用的模型提供方：请检查 ai_model_provider 的 enabled 与 API Key 配置");
        }
        log.debug("可用模型提供方链（按优先级）: {}",
                callable.stream().map(ProviderTarget::describe).toList());
        return callable;
    }

    /**
     * 按提供方编码取目标；找不到或不可用则回退到优先级最高的可用项。
     *
     * <p>这个方法是「用户在前端选了某个模型」的入口。注意它不会因为用户选了个
     * 没配 Key 的模型就报错，而是静默回退 —— 体感上比弹错误友好得多，
     * 同时会在响应里如实告诉前端最终用的是哪个模型（provider 字段）。
     */
    public ProviderTarget resolvePreferred(Long tenantId, String preferredCode) {
        List<ProviderTarget> callable = resolveCallable(tenantId);
        if (preferredCode != null && !preferredCode.isBlank()) {
            Optional<ProviderTarget> hit = callable.stream()
                    .filter(t -> t.providerCode().equalsIgnoreCase(preferredCode))
                    .findFirst();
            if (hit.isPresent()) {
                return hit.get();
            }
            // 用户选的提供方没配 Key 或已停用：不报错，回退并说明
            log.info("请求的提供方 [{}] 不可用（未配置 Key 或已停用），回退到 {}",
                    preferredCode, callable.get(0).describe());
        }
        return callable.get(0);
    }

    /**
     * 判断某个提供方编码对应的模型是否是 mock（离线模式）。
     *
     * <p>智能客服要用它决策：mock 模式下走规则意图识别，
     * 真实模型模式下走「模型决定调哪个工具」的循环。
     */
    public boolean isMock(Long tenantId, String providerCode) {
        try {
            return resolvePreferred(tenantId, providerCode).mock();
        } catch (Exception e) {
            return true;   // 都不可用时按离线处理，保证有输出
        }
    }

    /**
     * 拿到某个目标对应的 ChatModel 实例。
     *
     * <p><b>引擎选择逻辑（本方法的核心）</b>：
     * <ol>
     *   <li>{@code mock} 提供方 → <b>永远</b>用 {@link MockChatModel}（本地确定性回答）。
     *       这是「不填任何 API Key 也能离线跑通全链路」的根基，
     *       绝不能因为切换引擎而受影响 —— 所以这个分支放在最前面，且不受开关控制。</li>
     *   <li>其他提供方 + {@code app.ai.engine=spring-ai} → 用 {@link SpringAiChatModel}
     *       （内部走 Spring AI 的 {@code OpenAiChatModel}）。</li>
     *   <li>其他提供方 + {@code app.ai.engine=legacy}（默认）→ 用
     *       {@link OpenAiCompatChatModel}（自研 RestClient 实现）。</li>
     * </ol>
     * 三种情况返回的都是同一个 {@link ChatModel} 接口，业务层完全无感知。
     */
    public ChatModel toChatModel(ProviderTarget t) {
        // 1) mock 必须走本地实现：离线可演示是硬约束，不受引擎开关影响
        if (t.mock()) {
            return new MockChatModel();
        }
        // 2) Spring AI 引擎
        if (useSpringAiEngine()) {
            log.debug("使用 Spring AI 引擎构造模型: {}", t.describe());
            return springAiChatModelFactory.createAdapter(
                    t.providerCode(), t.baseUrl(), t.apiKey(), t.model());
        }
        // 3) 默认：自研适配层
        return new OpenAiCompatChatModel(t.providerCode(), t.baseUrl(), t.apiKey(), t.model(),
                restClient, objectMapper);
    }

    /** 当前是否启用 Spring AI 引擎（{@code app.ai.engine=spring-ai}） */
    public boolean useSpringAiEngine() {
        return "spring-ai".equalsIgnoreCase(props.getAi().getEngine());
    }

    /** 当前引擎名，供健康检查与管理端展示 */
    public String currentEngine() {
        return useSpringAiEngine() ? "spring-ai" : "legacy";
    }

    // ==================================================================
    //  带 Fallback 的调用
    // ==================================================================

    /**
     * 带自动 Fallback 的对话调用。
     *
     * <p>这是业务层最常用的方法。返回的 {@link ChatModel.ChatResult} 里带的
     * {@code provider} 是<b>实际成功的那一家</b>，不是请求的那一家 ——
     * 前端可以据此提示「已自动切换到备用模型」。
     *
     * @param preferredCode 期望的提供方编码，可为 null（表示按优先级取第一个可用的）
     * @param callType      记账用的调用类型（CHAT / TOOL ...）
     */
    public ChatModel.ChatResult chatWithFallback(Long tenantId, Long userId, Long sessionId, String traceId,
                                                 String preferredCode, String callType,
                                                 Function<ChatModel, ChatModel.ChatResult> invoker) {
        List<ProviderTarget> chain = resolveCallable(tenantId);

        // 如果指定了提供方，把它提到最前面（其余仍作为兜底）
        if (preferredCode != null && !preferredCode.isBlank()) {
            chain = reorder(chain, preferredCode);
        }

        List<String> failures = new ArrayList<>();
        for (ProviderTarget t : chain) {
            long start = System.currentTimeMillis();
            try {
                ChatModel model = toChatModel(t);
                ChatModel.ChatResult result = invoker.apply(model);
                long latency = System.currentTimeMillis() - start;
                callLogService.recordSuccess(tenantId, userId, sessionId, traceId, callType,
                        t.providerCode(), result.model(),
                        result.promptTokens(), result.completionTokens(),
                        result.latencyMs() > 0 ? result.latencyMs() : latency);
                if (!failures.isEmpty()) {
                    log.warn("模型 Fallback 生效：前 {} 次失败，最终由 {} 成功",
                            failures.size(), t.describe());
                }
                return result;
            } catch (Exception e) {
                long latency = System.currentTimeMillis() - start;
                String reason = shortMessage(e);
                failures.add(t.describe() + " → " + reason);
                callLogService.recordFailure(tenantId, userId, sessionId, traceId, callType,
                        t.providerCode(), t.model(), latency, reason);
                log.warn("模型调用失败，尝试下一个：{} 原因={}", t.describe(), reason);
            }
        }
        throw new BizException("所有模型提供方均调用失败：" + String.join(" | ", failures));
    }

    // ==================================================================
    //  工具方法
    // ==================================================================

    /** 把指定编码的提供方挪到链首，其余保持原顺序 */
    private List<ProviderTarget> reorder(List<ProviderTarget> chain, String code) {
        List<ProviderTarget> head = new ArrayList<>();
        List<ProviderTarget> tail = new ArrayList<>();
        for (ProviderTarget t : chain) {
            if (t.providerCode().equalsIgnoreCase(code)) {
                head.add(t);
            } else {
                tail.add(t);
            }
        }
        head.addAll(tail);
        return head;
    }

    /** 把数据库实体解析成可调用的目标（含 Key 解密） */
    private ProviderTarget toTarget(AiModelProvider e) {
        boolean mock = e.isMock();
        String key = mock ? "" : resolveApiKey(e);
        return new ProviderTarget(e, e.getProviderCode(), e.getBaseUrl(), key,
                e.getChatModel(), mock);
    }

    /**
     * 解析 API Key，优先级：JVM 系统属性 &gt; 环境变量 &gt; 数据库密文。
     *
     * <p>为什么环境变量优先于数据库：容器化部署时密钥由 Secret 注入，
     * 不应该要求运维再去管理端手工填一遍；而数据库里的密文是给「没有运维平台」
     * 的部署方式兜底的。两者共存时以外部注入为准，符合 12-Factor 原则。
     *
     * <p>为什么还要支持 JVM 系统属性（{@code -DDEEPSEEK_API_KEY=xxx}）：
     * Java 里 {@code System.getenv()} 读不到 {@code -D} 参数（那是系统属性，
     * 走 {@code System.getProperty()}）。这两种注入方式在运维实践中都很常见
     * （环境变量适合容器，{@code -D} 适合 systemd/启动脚本），
     * 只支持一种会让人排查很久「明明传了 Key 为什么说没配」。
     * 所以这里两种都读，且系统属性优先级最高（命令行显式指定最不容易搞错）。
     */
    public String resolveApiKey(AiModelProvider e) {
        String code = e.getProviderCode() == null ? "" : e.getProviderCode().toUpperCase();
        String[] names = {
                "APP_AI_" + code + "_API_KEY",
                code + "_API_KEY"
        };
        // 1) JVM 系统属性（-D 参数）
        for (String name : names) {
            String v = System.getProperty(name);
            if (v != null && !v.isBlank()) {
                log.debug("提供方 {} 使用 JVM 系统属性 {} 中的 API Key", e.getProviderCode(), name);
                return v.trim();
            }
        }
        // 2) 环境变量
        for (String name : names) {
            String v = System.getenv(name);
            if (v != null && !v.isBlank()) {
                log.debug("提供方 {} 使用环境变量 {} 中的 API Key", e.getProviderCode(), name);
                return v.trim();
            }
        }
        // 3) 数据库密文（AES 解密）
        String cipher = e.getApiKeyCipher();
        if (cipher != null && !cipher.isBlank()) {
            String plain = cryptoUtil.decrypt(cipher);
            if (plain != null && !plain.isBlank()) {
                return plain;
            }
            log.warn("提供方 {} 的 api_key_cipher 解密失败，视为未配置", e.getProviderCode());
        }
        return "";
    }

    /**
     * 判断 Key 的来源，供管理端展示（env / system / db / none）。
     *
     * <p>与 {@link #resolveApiKey} 的优先级必须完全一致，否则会出现
     * 「管理端显示已配置、实际调用说没配」这种最令人困惑的问题。
     */
    public String resolveKeySource(AiModelProvider e) {
        String code = e.getProviderCode() == null ? "" : e.getProviderCode().toUpperCase();
        for (String name : new String[]{"APP_AI_" + code + "_API_KEY", code + "_API_KEY"}) {
            String v = System.getProperty(name);
            if (v != null && !v.isBlank()) {
                return "system";
            }
        }
        for (String name : new String[]{"APP_AI_" + code + "_API_KEY", code + "_API_KEY"}) {
            String v = System.getenv(name);
            if (v != null && !v.isBlank()) {
                return "env";
            }
        }
        String cipher = e.getApiKeyCipher();
        if (cipher != null && !cipher.isBlank()) {
            String plain = cryptoUtil.decrypt(cipher);
            return (plain != null && !plain.isBlank()) ? "db" : "db(解密失败)";
        }
        return "none";
    }

    /** 提供方当前是否可用（供管理端接口展示状态用） */
    public boolean isCallable(AiModelProvider e) {
        return toTarget(e).callable();
    }

    /** 供健康检查/管理端展示：AI 服务探活用的 base url 等 */
    public AppProperties.AiService aiServiceConfig() {
        return props.getAiService();
    }

    /** 调用类型常量转发，避免业务层重复 import */
    public static final String CALL_TYPE_CHAT = AiCallLog.CallType.CHAT;

    private static String shortMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        if (msg == null) {
            msg = t.getClass().getSimpleName();
        }
        return msg.length() <= 300 ? msg : msg.substring(0, 300) + "...";
    }
}
