package com.yit.rag.service.ai.model;

import com.yit.rag.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ChatModel} 的 Spring AI 实现 —— 项目里的「第二种模型引擎」。
 *
 * <p>这个类解决什么问题：项目技术栈里写了 Spring AI，但多模型路由要求
 * 「按租户 + 按提供方在运行期动态决定 baseUrl / apiKey / model」。
 * 这个类把对话请求转发给 Spring AI 的 {@link OpenAiChatModel}，
 * 并把 Spring AI 的 {@link ChatResponse} 翻译回项目统一的 {@link ChatResult}。
 *
 * <h3>它和 {@link OpenAiCompatChatModel} 的关系：并存，不是替换</h3>
 * <ul>
 *   <li>{@link OpenAiCompatChatModel}：手写 {@code RestClient} 调
 *       {@code /v1/chat/completions}，协议细节完全自己掌控，
 *       <b>是默认引擎（legacy）</b>，也是经过完整回归验证的那条路径。</li>
 *   <li>{@code SpringAiChatModel}（本类）：同一件事交给 Spring AI 框架，
 *       享受它的消息模型、options 体系、usage 解析、未来可平滑接入
 *       Tool Calling / Advisor / 结构化输出等能力。</li>
 *   <li>两者都实现同一个 {@link ChatModel} 接口，由
 *       {@code app.ai.engine=legacy|spring-ai} 切换。
 *       业务层（ChatService / CustomerServiceAgent）对此完全无感知。</li>
 * </ul>
 *
 * <p><b>安全约束</b>：{@code mock} 提供方<b>永远</b>不会走到这个类 ——
 * {@link ModelRouter} 会把 mock 固定交给 {@link MockChatModel}，
 * 保证「没有 API Key 也能离线跑通全链路」这个根基不受引擎开关影响。
 */
@Slf4j
public class SpringAiChatModel implements ChatModel {

    private final SpringAiChatModelFactory factory;
    private final String providerCode;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public SpringAiChatModel(SpringAiChatModelFactory factory, String providerCode,
                             String baseUrl, String apiKey, String model) {
        this.factory = factory;
        this.providerCode = providerCode;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public ChatResult chat(String systemPrompt, String userMessage,
                           List<ChatTurn> history, Double temperature) {
        long start = System.currentTimeMillis();

        // 1) 取出（或构造）Spring AI 的模型实例
        OpenAiChatModel springAiModel = factory.getOrCreate(providerCode, baseUrl, apiKey, model);

        // 2) 项目自己的消息结构 → Spring AI 的 Message 体系
        List<Message> messages = new ArrayList<>(4 + (history == null ? 0 : history.size()));
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(systemPrompt));
        }
        if (history != null) {
            for (ChatTurn turn : history) {
                messages.add(toMessage(turn));
            }
        }
        messages.add(new UserMessage(userMessage == null ? "" : userMessage));

        // 3) 调用。注意 temperature 是「每次调用可覆盖」的，所以放进 Prompt 而不是默认 options
        Prompt prompt = (temperature == null)
                ? new Prompt(messages)
                : new Prompt(messages, OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(temperature)
                        .maxTokens(2048)
                        .build());

        ChatResponse response;
        try {
            response = springAiModel.call(prompt);
        } catch (Exception e) {
            // 统一翻译成业务异常：ModelRouter 靠这个异常做 Fallback 判定
            throw new BizException("[Spring AI] " + providerCode + " 调用失败: " + rootMessage(e), e);
        }
        long latency = System.currentTimeMillis() - start;

        if (response == null) {
            throw new BizException("[Spring AI] " + providerCode + " 返回空响应");
        }

        // 4) ChatResponse → 项目统一的 ChatResult
        Generation generation = response.getResult();
        String content = (generation != null && generation.getOutput() != null)
                ? generation.getOutput().getText() : null;
        if (content == null) {
            content = "";
        }

        int promptTokens = 0;
        int completionTokens = 0;
        String actualModel = model;
        if (response.getMetadata() != null) {
            Usage usage = response.getMetadata().getUsage();
            if (usage != null) {
                promptTokens = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
                completionTokens = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
            }
            if (response.getMetadata().getModel() != null) {
                actualModel = response.getMetadata().getModel();
            }
        }

        log.debug("[Spring AI] 调用完成: provider={} model={} promptTokens={} completionTokens={} latency={}ms",
                providerCode, actualModel, promptTokens, completionTokens, latency);

        return new ChatResult(content, providerCode, actualModel, promptTokens, completionTokens, latency);
    }

    /** 项目 ChatTurn → Spring AI Message */
    private Message toMessage(ChatTurn turn) {
        String role = turn.role() == null ? "user" : turn.role().toLowerCase();
        String content = turn.content() == null ? "" : turn.content();
        return switch (role) {
            case "system" -> new SystemMessage(content);
            case "assistant" -> new AssistantMessage(content);
            default -> new UserMessage(content);
        };
    }

    @Override
    public String providerCode() {
        return providerCode;
    }

    @Override
    public String modelName() {
        return model;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg;
    }
}
