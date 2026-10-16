package com.yit.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 企业级多模型 RAG 知识库与智能客服平台 —— 后端启动类。
 *
 * <p>这个类解决什么问题：把整个 Spring 容器拉起来，并声明几个全局能力：
 * <ul>
 *   <li>{@code @ConfigurationPropertiesScan}：让 {@code app.*} 配置直接绑定成强类型对象，
 *       而不是到处 {@code @Value} 拼字符串</li>
 *   <li>{@code @EnableAsync}：文档解析/建索引是耗时任务，放到独立线程池执行，
 *       保证上传接口秒回（状态机在后台推进）</li>
 *   <li>{@code @EnableScheduling}：给内存缓存做定期清理、给模型健康探活留位置</li>
 * </ul>
 *
 * <p>本机没有 Redis / MinIO 也能启动：缓存与对象存储都做了「可切换实现」，
 * dev profile 下走 memory + local 磁盘实现，见 application-dev.yml。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
@EnableScheduling
public class RagPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagPlatformApplication.class, args);
    }
}
