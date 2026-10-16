package com.yit.rag.service.ai.model;

import com.yit.rag.config.AppProperties;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring AI 的 {@link ChatModel} 实现 —— 自研适配层之外的「第二种引擎」。
 *
 * <p>这个类解决什么问题：项目最初的目标技术栈里写了 Spring AI，但多模型路由需要的是
 * 「按租户 + 按提供方在运行期动态决定 baseUrl / apiKey / model」，
 * 而项目里的 {@link OpenAiCompatChatModel} 是手写 HTTP 调用。
 * 这个类把同一件事改用 Spring AI 的 {@link OpenAiChatModel} 完成，
 * 通过 {@code app.ai.engine=legacy|spring-ai} 切换，两种实现行为等价、可互相替换。
 *
 * <h3>为什么自己 new OpenAiChatModel，而不用 spring-ai-starter-model-openai？</h3>
 * <p>这是本项目最容易讲错的一个点，也正好体现了真实工程与 demo 的差别：
 * <ul>
 *   <li>Spring AI 的 <b>starter 自动配置</b>面向的是「一个应用配一套模型凭据」的场景 ——
 *       它从 {@code spring.ai.openai.*} 配置里读 <b>一组</b> baseUrl/apiKey/model，
 *       启动时构造 <b>一个</b> Bean。</li>
 *   <li>而本项目是<b>多租户 + 多提供方</b>：{@code ai_model_provider} 表里每个租户可以有多条记录，
 *       priority 决定 Fallback 顺序，Key 还可能是 AES 密文。
 *       这些在启动时根本不知道（要看当前请求的租户），所以自动配置的模型不适用。</li>
 *   <li>结论：正确做法是用 Spring AI <b>底层的 API</b>（{@code OpenAiApi} +
 *       {@code OpenAiChatModel.Builder}）按需构造实例 —— 这也正是 Spring AI 官方文档里
 *       推荐的「多模型 / 动态模型」用法。starter 只是把这套 Builder 再包一层自动配置而已。</li>
 * </ul>
 *
 * <h3>两个刻意的配置决策</h3>
 * <ol>
 *   <li><b>RetryTemplate 设为「不重试」</b>：Spring AI 默认自带
 *       {@code RetryUtils.DEFAULT_RETRY_TEMPLATE}。但我们上层已经有
 *       {@code ModelRouter} 做「换 provider 重试」，如果底层再叠一层重试，
 *       失败时的等待时间会被放大数倍（重试次数相乘），
 *       Fallback 的响应速度会明显变慢。所以这里显式设成 maxAttempts=1，
 *       把「重试」这件事的唯一负责人交给 ModelRouter。</li>
 *   <li><b>ObservationRegistry 用 NOOP</b>：不接指标系统时避免引入观测开销；
 *       将来要接 Prometheus/Micrometer Tracing 时，把这里的 NOOP 换成注入的
 *       {@code ObservationRegistry} 即可，改动只有一行。</li>
 * </ol>
 */
@Slf4j
@Component
public class SpringAiChatModelFactory {

    /**
     * 已完成构造的 ChatModel 缓存。
     *
     * <p>为什么要缓存：{@code OpenAiChatModel} 内部持有 HTTP 客户端（RestClient）、
     * 重试模板、工具调用管理器等。每次对话都新建会有两个代价：
     * ①对象分配与连接池无法复用；②Spring AI 构造时会做一些校验与准备，有固定开销。
     * 这一层缓存让「同一组配置只构造一次」。
     *
     * <p>key 里必须带上 baseUrl/apiKey/model：模型配置可以在管理端被热更新
     * （{@code ModelAdminService.update}），带上这些字段后，配置一变 key 就变，
     * 自然拿到新实例，不会出现「改了 Key 还在用旧连接」的问题。
     */
    private final Map<String, OpenAiChatModel> cache = new ConcurrentHashMap<>();

    private final AppProperties props;
    private final RestClient.Builder springAiRestClientBuilder;

    public SpringAiChatModelFactory(AppProperties props,
                                    @Qualifier("springAiRestClientBuilder") RestClient.Builder springAiRestClientBuilder) {
        this.props = props;
        this.springAiRestClientBuilder = springAiRestClientBuilder;
    }

    @PostConstruct
    void init() {
        log.info("Spring AI 引擎已注册（app.ai.engine={}）。" +
                        "可用 provider_code=spring-ai 切换到 Spring AI 的 OpenAiChatModel 实现；" +
                        "mock 提供方始终走本地 MockChatModel，保证离线可演示",
                props.getAi().getEngine());
    }

    /**
     * 构造或复用一个 Spring AI 的 ChatModel。
     *
     * @param providerCode 提供方编码（deepseek / dashscope / openai）
     * @param baseUrl      OpenAI 兼容的 base url
     * @param apiKey       API Key（可为空字符串，此时能构造成功但调用会失败 ——
     *                     这正是「证明代码路径可达」时需要的异常路径）
     * @param model        对话模型名
     */
    public OpenAiChatModel getOrCreate(String providerCode, String baseUrl, String apiKey, String model) {
        String cacheKey = providerCode + '|' + baseUrl + '|' + model + '|' + safeHash(apiKey);
        return cache.computeIfAbsent(cacheKey, k -> build(providerCode, baseUrl, apiKey, model));
    }

    /**
     * 产出一个符合本项目 {@link ChatModel} 接口的适配器。
     *
     * <p>这就是「新增实现而不是替换」的落点：{@link ModelRouter} 拿到的是同一个
     * {@link ChatModel} 接口，内部是走手写 HTTP 还是走 Spring AI 由引擎开关决定，
     * 业务层（ChatService / CustomerServiceAgent）完全无感知。
     */
    public ChatModel createAdapter(String providerCode, String baseUrl, String apiKey, String model) {
        return new SpringAiChatModel(this, providerCode, baseUrl, apiKey, model);
    }

    /**
     * 实际构造：OpenAiApi（HTTP 端点 + 认证）+ OpenAiChatModel（对话语义）。
     *
     * <p>真正的「对话调用 + 响应翻译」逻辑在 {@link SpringAiChatModel} 适配器里，
     * 这个类只负责「按配置把实例造出来并缓存」—— 职责单一，也便于单独测试。
     */
    private OpenAiChatModel build(String providerCode, String baseUrl, String apiKey, String model) {
        log.info("[Spring AI] 构造 OpenAiChatModel: provider={} baseUrl={} model={} apiKey={}",
                providerCode, baseUrl, model, apiKey == null || apiKey.isBlank() ? "(未配置)" : "(已配置)");

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey == null ? "" : apiKey)
                // 复用我们统一配置了超时的 RestClient.Builder（见 RestClientConfig）：
                // 否则 Spring AI 会用默认无超时的客户端，模型卡住时业务线程会被无限期挂住
                .restClientBuilder(springAiRestClientBuilder.clone())
                .build();

        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
                .model(model)
                .temperature(0.3)
                .maxTokens(2048)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(defaultOptions)
                // 不叠加重试：重试由 ModelRouter 的跨 provider Fallback 负责，避免延迟被放大
                .retryTemplate(RetryTemplate.builder()
                        .maxAttempts(1)
                        .build())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }

    /** 当前已缓存的模型实例数（供健康检查/管理端观测缓存效果） */
    public int cachedInstanceCount() {
        return cache.size();
    }

    /** Key 只用于做缓存 key 的一部分，这里取哈希避免明文 Key 出现在内存键里 */
    private static String safeHash(String apiKey) {
        return apiKey == null ? "null" : Integer.toHexString(apiKey.hashCode());
    }
}
