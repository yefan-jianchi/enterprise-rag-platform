package com.yit.rag.service.storage;

import com.yit.rag.common.exception.BizException;
import com.yit.rag.config.AppProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 本地磁盘存储实现（本机默认）。
 *
 * <p>这个类解决什么问题：让「没有 MinIO」的机器也能完整跑通文档上传 → 解析 → 建索引。
 * 文件直接落在 {@code app.storage.local-dir} 下，按 objectKey 组织目录结构。
 *
 * <p>三个必须处理的工程细节：
 * <ol>
 *   <li><b>路径穿越防护</b>：objectKey 里如果混进 {@code ../}，就能写到目录外面去。
 *       这里统一做 {@code normalize + startsWith(root)} 校验，越界直接拒绝。</li>
 *   <li><b>相对路径与工作目录</b>：{@code ./data/files} 是相对「进程工作目录」的，
 *       用 IDEA 跑和用 java -jar 跑的 cwd 可能不同。所以启动时把解析后的绝对路径打出来，
 *       出问题一眼能定位。</li>
 *   <li><b>先写临时文件再原子移动</b>：直接往目标文件写，中途失败会留下半截文件，
 *       后续解析会报「格式错误」，排查方向完全被带偏。用 {@code Files.move(ATOMIC_MOVE)} 解决。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorageService implements FileStorageService {

    private static final String TEMP_DIR_NAME = ".tmp";

    private final AppProperties props;

    /** 根目录的绝对规范路径 */
    private Path root;

    @PostConstruct
    void init() {
        String configured = props.getStorage().getLocalDir();
        this.root = Paths.get(configured).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            Files.createDirectories(root.resolve(TEMP_DIR_NAME));
        } catch (IOException e) {
            throw new IllegalStateException("无法创建本地存储目录: " + root, e);
        }
        log.info("本地文件存储已启用（app.storage.type=local）：root={}", root);
    }

    @Override
    public String save(String objectKey, InputStream in, long size, String contentType) {
        Path target = resolveSafely(objectKey);
        try {
            Files.createDirectories(target.getParent());
            // 先写临时文件，再原子移动：避免半截文件被当成完整文档去解析
            Path tmp = root.resolve(TEMP_DIR_NAME)
                    .resolve(System.nanoTime() + "_" + target.getFileName());
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // 跨文件系统时 ATOMIC_MOVE 不被支持，退回普通移动
                log.debug("原子移动失败，退回普通移动: {}", atomicFailed.getMessage());
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.debug("文件已保存: {} ({} bytes) -> {}", objectKey, size, target);
            return objectKey;
        } catch (IOException e) {
            throw new BizException("文件保存失败: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] read(String objectKey) {
        Path p = resolveSafely(objectKey);
        if (!Files.exists(p)) {
            throw BizException.notFound("文件不存在: " + objectKey);
        }
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new BizException("文件读取失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream openStream(String objectKey) {
        Path p = resolveSafely(objectKey);
        if (!Files.exists(p)) {
            throw BizException.notFound("文件不存在: " + objectKey);
        }
        try {
            return Files.newInputStream(p);
        } catch (IOException e) {
            throw new BizException("文件读取失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String absolutePath(String objectKey) {
        Path p = resolveSafely(objectKey);
        if (!Files.exists(p)) {
            throw BizException.notFound("文件不存在: " + objectKey);
        }
        // 返回「反斜杠转义后的」绝对路径：Windows 上传给 Python 服务用正斜杠更稳
        return p.toString();
    }

    @Override
    public boolean delete(String objectKey) {
        try {
            Path p = resolveSafely(objectKey);
            boolean deleted = Files.deleteIfExists(p);
            if (deleted) {
                log.debug("文件已删除: {}", p);
            }
            return deleted;
        } catch (IOException e) {
            log.warn("文件删除失败 {}: {}", objectKey, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean exists(String objectKey) {
        try {
            return Files.exists(resolveSafely(objectKey));
        } catch (BizException e) {
            return false;
        }
    }

    @Override
    public String implementation() {
        return "local";
    }

    /** 暴露根目录，便于运维排查与健康检查展示 */
    public String rootPath() {
        return root.toString();
    }

    /**
     * 把 objectKey 安全地解析成 root 下的绝对路径。
     *
     * <p>这是安全边界：任何试图跳出 root 的 key（{@code ../}、绝对路径）都会被拒绝。
     */
    private Path resolveSafely(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw BizException.badRequest("objectKey 不能为空");
        }
        // 统一分隔符，兼容 Windows 上传进来的反斜杠
        String normalizedKey = objectKey.replace('\\', '/');
        Path p = root.resolve(normalizedKey).normalize();
        if (!p.startsWith(root)) {
            log.warn("检测到非法的存储路径访问: {}", objectKey);
            throw BizException.forbidden("非法的文件路径");
        }
        return p;
    }
}
