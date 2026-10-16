package com.yit.rag.service.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yit.rag.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容协议的聊天模型实现。
 *
 * <p>这个类解决什么问题：DeepSeek、通义千问（阿里百炼兼容模式）、OpenAI
 * 三家虽然厂商不同，但都提供 <b>完全相同的</b> {@code POST /v1/chat/completions} 接口：
 * 请求体是 {@code {model, messages, temperature, max_tokens}}，
 * 响应体是 {@code {choices[0].message.content, usage{prompt_tokens, completion_tokens}}}。
 *
 * <p>所以「多模型适配」的正确做法不是写三个适配器类，而是：
 * <ol>
 *   <li>识别出共同协议 → 一个实现类搞定三家</li>
 *   <li>把差异（baseUrl / apiKey / model）外置到 {@code ai_model_provider} 表</li>
 *   <li>新增一家厂商时，只插一条数据，不需要改代码、不需要发版</li>
 * </ol>
 * 这是本项目在架构上最值得讲的一点。
 *
 * <p>刻意不依赖 Spring AI：Spring AI 目前版本迭代快、抽象层厚，
 * 排查「到底是我的代码慢还是框架慢」很费劲。自己用 RestClient 写 100 行，
 * 协议、超时、错误处理全在掌控内，也更好讲清楚。
 *
 * <p>这个类不是 Spring Bean —— 它由 {@link ModelRouter} 按数据库配置动态创建，
 * 因为「用哪个 baseUrl / 哪个 Key」是运行期数据而不是编译期配置。
 */
@Slf4j
public class OpenAiCompatChatModel implements ChatModel {

    private final String providerCode;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final RestClient client;
    private final ObjectMapper objectMapper;

    public OpenAiCompatChatModel(String providerCode, String baseUrl, String apiKey, String model,
                                 RestClient client, ObjectMapper objectMapper) {
        this.providerCode = providerCode;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatResult chat(String systemPrompt, String userMessage, List<ChatTurn> history, Double temperature) {
        long start = System.currentTimeMillis();
        try {
            ObjectNode body = buildRequestBody(systemPrompt, userMessage, history, temperature);
            String responseJson = client.post()
                    .uri(baseUrl + "/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);

            long latency = System.currentTimeMillis() - start;
            return parseResponse(responseJson, latency);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(providerCode + " 模型调用失败: " + rootMessage(e), e);
        }
    }

    // ==================================================================

    /** 拼 OpenAI 兼容的请求体：{model, messages[], temperature} */
    private ObjectNode buildRequestBody(String systemPrompt, String userMessage,
                                        List<ChatTurn> history, Double temperature) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature == null ? 0.3 : temperature);
        body.put("stream", false);

        ArrayNode messages = body.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(messageNode("system", systemPrompt));
        }
        if (history != null) {
            for (ChatTurn turn : history) {
                messages.add(messageNode(turn.role(), turn.content()));
            }
        }
        messages.add(messageNode("user", userMessage));
        return body;
    }

    private ObjectNode messageNode(String role, String content) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", role);
        node.put("content", content == null ? "" : content);
        return node;
    }

    /** 解析响应：内容在 choices[0].message.content，用量在 usage */
    private ChatResult parseResponse(String responseJson, long latency) throws Exception {
        JsonNode root = objectMapper.readTree(responseJson);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new BizException(providerCode + " 返回结果中没有 choices，响应片段: "
                    + abbreviate(responseJson));
        }
        String content = choices.get(0).path("message").path("content").asText("");
        JsonNode usage = root.path("usage");
        return new ChatResult(
                content,
                providerCode,
                root.path("model").asText(model),
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                latency);
    }

    @Override
    public String providerCode() {
        return providerCode;
    }

    @Override
    public String modelName() {
        return model;
    }

    /** 供日志/前端展示脱敏后的配置 */
    public String describe() {
        return providerCode + "{" + baseUrl + ", model=" + model + "}";
    }

    /** 组装历史消息的便捷方法 */
    public static List<ChatTurn> history() {
        return new ArrayList<>();
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
