package com.yit.rag.config;

import com.yit.rag.service.ai.AiServiceClient;
import com.yit.rag.service.ai.dto.AiDtos;
import com.yit.rag.service.cache.CacheService;
import com.yit.rag.service.storage.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动自检 —— 把「跑起来之后才发现的问题」提前到启动日志里。
 *
 * <p>这个类解决什么问题：本地开发最容易浪费时间的场景是
 * 「服务起来了，但一调接口就报错」，然后开始翻日志找原因。
 * 常见的坑有四个：
 * <ol>
 *   <li>Python AI 服务（8001）没启动 → 上传文档、问答全部失败</li>
 *   <li>MinIO 没起（如果配了 minio）→ 上传文件失败</li>
 *   <li>Redis 没起（如果配了 redis）→ 缓存报错</li>
 *   <li>文件存到了哪个目录？含中文的路径会让 FAISS 报错，需要提前看见</li>
 * </ol>
 * 所以启动时主动探一遍并打印中文结论，让人一眼知道「现在这套能不能用」。
 *
 * <p>关键原则：<b>自检失败绝不阻断启动</b>。因为「只想看看接口文档」、
 * 「AI 服务稍后才启动」都是合理场景，不让起反而更烦人。
 * 用 {@code @Order(2)} 排在管理员引导（{@code @Order(1)}）之后执行。
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class StartupHealthLogger implements CommandLineRunner {

    private final AppProperties props;
    private final AiServiceClient aiServiceClient;
    private final CacheService cacheService;
    private final FileStorageService fileStorageService;

    @Value("${server.port:8080}")
    private String serverPort;

    @Override
    public void run(String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("============================================================\n");
        sb.append("  企业级多模型 RAG 知识库与智能客服平台 · 后端启动自检\n");
        sb.append("============================================================\n");
        sb.append("  接口地址   : http://localhost:").append(serverPort).append("\n");
        sb.append("  接口文档   : http://localhost:").append(serverPort).append("/swagger-ui.html\n");
        sb.append("  健康检查   : http://localhost:").append(serverPort).append("/api/health\n");
        sb.append("  缓存实现   : ").append(cacheService.implementation())
                .append("   (app.cache.type=").append(props.getCache().getType()).append(")\n");
        sb.append("  存储实现   : ").append(fileStorageService.implementation())
                .append("   (app.storage.type=").append(props.getStorage().getType()).append(")\n");

        // ---- 探活 AI 服务 ----
        sb.append("  AI 服务    : ").append(props.getAiService().getBaseUrl());
        AiDtos.HealthResponse health = props.getAiService().isHealthCheckOnStartup() ? aiServiceClient.health() : null;
        if (health == null) {
            sb.append("  [未连通]\n");
            sb.append("     ⚠ 文档解析 / 建索引 / 问答 都会失败。请先启动 AI 服务：\n");
            sb.append("       cd ai-service && python -m uvicorn app.main:app --host 0.0.0.0 --port 8001\n");
        } else {
            sb.append("  [已连通]\n");
            sb.append("     · 向量提供方 : ").append(health.embedProvider()).append("\n");
            sb.append("     · 模型提供方 : ").append(health.llmProvider()).append("\n");
            sb.append("     · FAISS 可用 : ").append(health.faissAvailable()).append("\n");
            sb.append("     · 数据目录   : ").append(health.dataDir()).append("\n");
        }
        sb.append("============================================================");
        log.info(sb.toString());
    }
}
