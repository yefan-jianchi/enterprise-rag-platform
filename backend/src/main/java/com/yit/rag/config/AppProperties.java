package com.yit.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code app.*} 配置的强类型映射。
 *
 * <p>这个类解决什么问题：把所有自定义配置收敛成对象，好处有三点：
 * <ol>
 *   <li>IDE 能补全、能跳转，改配置不用全局搜字符串</li>
 *   <li>类型安全：超时时间是 long 而不是 String，避免运行期 {@code parseInt} 炸掉</li>
 *   <li>默认值写在代码里，配置文件不写也能跑（本地开发友好）</li>
 * </ol>
 */
@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** Python AI 服务（文档解析 / 索引 / 检索 / 模型调用） */
    private AiService aiService = new AiService();

    /** 模型调用引擎选择：legacy（自研适配层）| spring-ai */
    private Ai ai = new Ai();

    /** 缓存实现选择：memory | redis */
    private Cache cache = new Cache();

    /** 对象存储实现选择：local | minio */
    private Storage storage = new Storage();

    /** JWT 参数 */
    private Jwt jwt = new Jwt();

    /** 管理员引导（首次启动建 admin 账号） */
    private Bootstrap bootstrap = new Bootstrap();

    /** api_key_cipher 的 AES 密钥 */
    private Crypto crypto = new Crypto();

    /** 模型单价（元 / 千 token），用于折算 cost_yuan */
    private ModelPrice modelPrice = new ModelPrice();

    /** RAG 业务默认值 */
    private Rag rag = new Rag();

    // ====================================================================

    @Data
    public static class AiService {
        private String baseUrl = "http://localhost:8001";
        private long connectTimeoutMs = 5000;
        private long readTimeoutMs = 180_000;
        private boolean healthCheckOnStartup = true;
    }

    /**
     * 模型调用引擎选择。
     *
     * <p>这个类解决什么问题：同一套业务代码要能在「自研 HTTP 适配层」与
     * 「Spring AI 框架」两个引擎之间切换，用于技术选型对比、灰度与回滚。
     *
     * <ul>
     *   <li>{@code legacy}（默认）：自研 {@code OpenAiCompatChatModel} +
     *       {@code MockChatModel}。协议细节自己掌控，是经过完整回归验证的路径。</li>
     *   <li>{@code spring-ai}：{@code SpringAiChatModel}（内部用 Spring AI 的
     *       {@code OpenAiChatModel}）。享受框架的消息模型、options、usage 解析，
     *       以及未来可平滑接入 Tool Calling / Advisor / 结构化输出。</li>
     * </ul>
     *
     * <p><b>重要</b>：无论哪个引擎，{@code mock} 提供方<b>永远</b>走本地
     * {@code MockChatModel}。这是「不填任何 API Key 也能离线跑通全链路」的根基，
     * 不受引擎开关影响。
     */
    @Data
    public static class Ai {
        /** legacy | spring-ai */
        private String engine = "legacy";
    }

    @Data
    public static class Cache {
        /** memory = 进程内 Map（本机默认）；redis = 真 Redis */
        private String type = "memory";
        /** 内存实现的最大 key 数，防无界增长 */
        private int memoryMaxSize = 10_000;
        /** 默认过期秒数 */
        private long defaultTtlSeconds = 300;
    }

    @Data
    public static class Storage {
        /** local = 本地磁盘（本机默认）；minio = 对象存储 */
        private String type = "local";
        /** local 实现的根目录 */
        private String localDir = "./data/files";
        private Minio minio = new Minio();
    }

    @Data
    public static class Minio {
        private String endpoint = "http://localhost:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String bucket = "rag-platform";
    }

    @Data
    public static class Jwt {
        private String secret;
        private long expireMinutes = 720;
        private String header = "Authorization";
        private String prefix = "Bearer ";
    }

    @Data
    public static class Bootstrap {
        private boolean enabled = true;
        private String tenantCode = "demo";
        private String username = "admin";
        private String password = "admin123";
        private String nickname = "系统管理员";
    }

    @Data
    public static class Crypto {
        private String aesKey;
    }

    /**
     * 单价表：{@code app.model-price.<provider>.prompt / completion}（单位：元 / 千 token）。
     * 未配置的提供方走 {@code default}。
     */
    @Data
    public static class ModelPrice {
        /**
         * 形态：{@code app.model-price.deepseek.prompt} / {@code .completion}
         * （yml 里直接写提供方名，不额外套一层 providers，配置更直观）。
         * 约定保留 key {@code default} 作为兜底单价。
         */
        private Map<String, Price> providers = new LinkedHashMap<>();

        /** 取某个提供方的单价，找不到就用 default，再找不到用兜底常量 */
        public Price of(String providerCode) {
            if (providerCode != null) {
                Price p = providers.get(providerCode.toLowerCase());
                if (p != null) {
                    return p;
                }
            }
            Price fallback = providers.get("default");
            return fallback != null ? fallback : new Price(0.001, 0.002);
        }
    }

    /** 单价（元 / 千 token） */
    @Data
    public static class Price {
        private double prompt;
        private double completion;

        public Price() {
        }

        public Price(double prompt, double completion) {
            this.prompt = prompt;
            this.completion = completion;
        }
    }

    @Data
    public static class Rag {
        private int defaultTopK = 5;
        private String defaultStrategy = "HYBRID_RERANK";
        private int processMaxRetry = 1;
    }
}
