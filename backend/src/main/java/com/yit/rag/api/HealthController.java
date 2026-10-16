package com.yit.rag.api;

import com.yit.rag.common.R;
import com.yit.rag.service.ai.AiServiceClient;
import com.yit.rag.service.ai.dto.AiDtos;
import com.yit.rag.service.cache.CacheService;
import com.yit.rag.service.storage.FileStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统健康检查接口。
 *
 * <p>这个类解决什么问题：演示和排障时最先要确认的是「后端好不好、AI 服务通不通、
 * 当前用的是内存缓存还是 Redis」。把这三件事一次返回，比逐个去猜快得多。
 *
 * <p>尤其是 {@code cache} / {@code storage} 两个字段：它们直接告诉你当前跑的是
 * 「本机零依赖模式」还是「生产模式」，演示时一眼就能讲清楚可切换实现的效果。
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "系统", description = "健康检查")
public class HealthController {

    private final AiServiceClient aiServiceClient;
    private final CacheService cacheService;
    private final FileStorageService fileStorageService;

    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "返回后端状态、缓存/存储实现、AI 服务连通性")
    public R<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "ok");
        data.put("app", "enterprise-rag-backend");
        data.put("cache", cacheService.implementation());
        data.put("storage", fileStorageService.implementation());

        AiDtos.HealthResponse ai = aiServiceClient.health();
        if (ai == null) {
            data.put("aiService", Map.of("status", "unreachable"));
            data.put("aiServiceReachable", false);
        } else {
            Map<String, Object> aiInfo = new LinkedHashMap<>();
            aiInfo.put("status", ai.status());
            aiInfo.put("app", ai.app());
            aiInfo.put("version", ai.version());
            aiInfo.put("embedProvider", ai.embedProvider());
            aiInfo.put("llmProvider", ai.llmProvider());
            aiInfo.put("faissAvailable", ai.faissAvailable());
            aiInfo.put("dataDir", ai.dataDir());
            data.put("aiService", aiInfo);
            data.put("aiServiceReachable", true);
        }
        return R.ok(data);
    }
}
