package com.yit.rag.service.ai.model;

import com.yit.rag.service.ai.dto.AiDtos;

import java.util.List;

/**
 * 统一的聊天模型接口 —— 多模型适配层的对外契约。
 *
 * <p>这个类解决什么问题：接入 DeepSeek / 通义千问 / OpenAI / 本地 Mock 四家，
 * 如果每个调用点都写 if-else 判断厂商，代码会烂得没法维护。
 * 这里定义一个统一的 {@code chat} 方法，业务层永远只看到这一个接口。
 *
 * <p>为什么不需要为每家写一个实现类？因为这三家都提供 <b>OpenAI 兼容协议</b>
 * （{@code /v1/chat/completions}），请求体结构完全一致，
 * 差异只有三样：{@code baseUrl}、{@code apiKey}、{@code model}。
 * 所以代码里只有一个 {@code OpenAiCompatChatModel}，差异全部外置到
 * {@code ai_model_provider} 表里 —— 这是「适配层」最优雅的形态：
 * 不是给每个厂商写适配器，而是识别出共同协议后把差异变成配置。
 *
 * <p>本地的 {@code mock} 提供方也走同一个接口，内部直接返回确定性回答，
 * 保证「没有 API Key 也能完整演示」。
 */
public interface ChatModel {

    /**
     * 一次对话补全。
     *
     * @param systemPrompt 系统提示词（RAG 场景下是「参考资料 + 指令」）
     * @param userMessage  用户问题
     * @param history      历史消息（可为空），用于多轮对话
     * @param temperature  温度，null 用配置默认值
     */
    ChatResult chat(String systemPrompt, String userMessage, List<ChatTurn> history, Double temperature);

    /** 提供方编码：deepseek / dashscope / openai / mock */
    String providerCode();

    /** 实际使用的模型名 */
    String modelName();

    // ==================================================================

    /** 一条对话消息 */
    record ChatTurn(String role, String content) {
        public static ChatTurn user(String content) {
            return new ChatTurn("user", content);
        }

        public static ChatTurn assistant(String content) {
            return new ChatTurn("assistant", content);
        }

        public static ChatTurn system(String content) {
            return new ChatTurn("system", content);
        }
    }

    /** 统一返回结构：不管哪家模型，拿到的都是这一套字段 */
    record ChatResult(
            String content,
            String provider,
            String model,
            int promptTokens,
            int completionTokens,
            long latencyMs
    ) {
        public int totalTokens() {
            return promptTokens + completionTokens;
        }

        /** 把 AI 服务返回的引用转成这里的结果（有些链路会带上） */
        public static ChatResult of(AiDtos.RagAskResponse r) {
            return new ChatResult(
                    r.answer(),
                    r.provider(),
                    r.model(),
                    r.promptTokens() == null ? 0 : r.promptTokens(),
                    r.completionTokens() == null ? 0 : r.completionTokens(),
                    r.elapsedMs() == null ? 0 : r.elapsedMs());
        }
    }
}
