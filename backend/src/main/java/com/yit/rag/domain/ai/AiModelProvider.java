package com.yit.rag.domain.ai;

import com.yit.rag.domain.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicInsert;

/**
 * 模型提供方配置（对应 {@code ai_model_provider}）—— 多模型适配层的数据来源。
 *
 * <p>这个类解决什么问题：DeepSeek / 通义 / OpenAI 三家都提供 OpenAI 兼容协议，
 * 差异只有 {@code baseUrl + apiKey + model} 三件套。所以适配层不需要为每家写一个类，
 * 而是把差异抽到这张表里，代码里只有一个 {@code ChatModel} 实现。
 *
 * <p>两个关键设计：
 * <ol>
 *   <li>{@code priority} 数值越小越优先。调用失败（超时/异常）时
 *       {@code ModelRouter} 就按这个顺序往后找下一个可用提供方，实现 Fallback。</li>
 *   <li>{@code apiKeyCipher} 存的是 AES 密文，绝不落明文。读取优先级：
 *       环境变量（如 {@code DEEPSEEK_API_KEY}） > 库里密文。</li>
 * </ol>
 */
@Getter
@Setter
@Entity
@DynamicInsert
@Table(name = "ai_model_provider")
public class AiModelProvider extends CreatedEntity {

    /** 0 表示平台级公共模型，对所有租户可用 */
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 0L;

    /** openai / deepseek / dashscope / mock */
    @Column(name = "provider_code", nullable = false, length = 48)
    private String providerCode;

    @Column(name = "provider_name", nullable = false, length = 64)
    private String providerName;

    /** OpenAI 兼容的 base url，例如 https://api.deepseek.com/v1 */
    @Column(name = "base_url", nullable = false, length = 255)
    private String baseUrl;

    /** AES 加密后的 API Key */
    @Column(name = "api_key_cipher", length = 512)
    private String apiKeyCipher;

    /** 对话模型名，如 deepseek-chat */
    @Column(name = "chat_model", nullable = false, length = 64)
    private String chatModel;

    @Column(name = "embed_model", length = 64)
    private String embedModel;

    @Column(name = "embed_dim")
    private Integer embedDim;

    /** 数值越小越优先（Fallback 排序依据） */
    @Column(name = "priority", nullable = false)
    private Integer priority = 100;

    /** 1 启用 / 0 停用 */
    @Column(name = "enabled", nullable = false)
    private Integer enabled = 1;

    /** 温度、max_tokens 等扩展参数（JSON 字符串） */
    @Column(name = "extra_json", columnDefinition = "JSON")
    private String extraJson;

    /** 是否为「不需要 Key」的本地模拟提供方 */
    public boolean isMock() {
        return "mock".equalsIgnoreCase(providerCode);
    }
}
