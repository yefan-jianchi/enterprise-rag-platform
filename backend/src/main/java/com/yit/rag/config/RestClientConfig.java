package com.yit.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 调用 Python AI 服务的 {@link RestClient} 配置。
 *
 * <p>这个类解决什么问题：Spring 6.1 起 {@code RestClient} 是官方推荐的同步 HTTP 客户端
 * （RestTemplate 进入维护态，WebClient 是响应式、在 MVC 项目里用着别扭）。
 * 但默认的 RestClient 没有超时 —— 一旦 AI 服务卡住，业务线程会被无限期挂住，
 * 最终 Tomcat 线程池打满、整个服务不可用。所以这里显式配置连接与读取超时。
 *
 * <p>两个超时的区别（面试常问）：
 * <ul>
 *   <li><b>connectTimeout</b>：TCP 建连时间。AI 服务在本地/同内网，5 秒足够，
 *       超时说明服务没起来或网络不通</li>
 *   <li><b>readTimeout</b>：等响应的时间。文档解析、建索引、大模型生成都可能很慢，
 *       所以给到 180 秒。这个值不能太小，否则「大文档解析」会被误判为失败</li>
 * </ul>
 *
 * <p>底层用 JDK 自带的 {@code HttpClient}（Java 11+），不额外引入 OkHttp/Apache HttpClient，
 * 少一个依赖少一份 CVE 维护成本。
 */
@Slf4j
@Configuration
public class RestClientConfig {

    /**
     * 专门给 AI 服务用的 RestClient，带 baseUrl 与超时。
     */
    @Bean("aiRestClient")
    public RestClient aiRestClient(AppProperties props) {
        AppProperties.AiService cfg = props.getAiService();

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(cfg.getConnectTimeoutMs()))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(cfg.getReadTimeoutMs()));

        log.info("AI 服务客户端初始化：baseUrl={} connectTimeout={}ms readTimeout={}ms",
                cfg.getBaseUrl(), cfg.getConnectTimeoutMs(), cfg.getReadTimeoutMs());

        return RestClient.builder()
                .baseUrl(cfg.getBaseUrl())
                .requestFactory((ClientHttpRequestFactory) factory)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * 供 Spring AI 使用的 {@link RestClient.Builder}（不带 baseUrl）。
     *
     * <p>为什么需要单独一个 Bean：Spring AI 的 {@code OpenAiApi.Builder} 需要的是
     * <b>Builder</b> 而不是已经绑定了 baseUrl 的客户端 —— 因为 baseUrl 由 Spring AI
     * 自己按 provider 设置。而 {@link #aiRestClient} 已经绑定了 Python AI 服务的 baseUrl，
     * 无法复用。
     *
     * <p>但「超时必须一致」：如果不显式配置，Spring AI 会用它内置的、<b>没有读超时</b>的
     * 客户端，模型端一旦卡住，业务线程会被无限期挂住，最终把 Tomcat 线程池拖死。
     * 所以这里复用同一套超时参数，只是不设 baseUrl。
     *
     * <p>Spring AI 内部会对这个 Builder 调用 {@code clone()}，所以它是可安全复用的原型。
     */
    @Bean("springAiRestClientBuilder")
    public RestClient.Builder springAiRestClientBuilder(AppProperties props) {
        AppProperties.AiService cfg = props.getAiService();

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(cfg.getConnectTimeoutMs()))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(cfg.getReadTimeoutMs()));

        log.info("Spring AI RestClient.Builder 已就绪（与 AI 服务客户端共用同一套超时参数）");
        return RestClient.builder().requestFactory(factory);
    }
}
