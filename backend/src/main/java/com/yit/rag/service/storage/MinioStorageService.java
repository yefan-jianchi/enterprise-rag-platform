package com.yit.rag.service.storage;

import com.yit.rag.common.exception.BizException;
import com.yit.rag.config.AppProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * MinIO 对象存储实现（生产 / Docker 环境使用）。
 *
 * <p>这个类解决什么问题：本地磁盘存储在多实例部署下是致命的 ——
 * A 实例上传的文件，B 实例读不到，负载均衡一转就 404。
 * 生产必须用共享对象存储，MinIO 是 S3 兼容协议，代码不用改就能上云（阿里云 OSS / AWS S3）。
 *
 * <p>实现要点：
 * <ol>
 *   <li><b>bucket 自愈</b>：启动时检查 bucket 是否存在，不存在就建。
 *       避免「部署完第一次上传才发现 bucket 没建」这种低级事故。</li>
 *   <li><b>absolutePath 的语义</b>：AI 服务的解析接口要的是本地文件路径，
 *       而对象存储里的文件没有本地路径。所以这里把对象下载到本地临时目录再返回路径。
 *       代价是一次磁盘 IO，换来的是「存储实现切换对业务完全透明」。</li>
 *   <li><b>流式上传的大小未知问题</b>：MinIO 的 putObject 需要知道 size，
 *       -1 表示由 SDK 分片上传自动处理。</li>
 * </ol>
 *
 * <p>注意：这个类只有配置 {@code app.storage.type=minio} 时才会被创建，
 * 本机没有 MinIO 也不会因为它启动失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.storage.type", havingValue = "minio")
public class MinioStorageService implements FileStorageService {

    private final AppProperties props;

    private MinioClient client;
    private String bucket;

    @PostConstruct
    void init() {
        AppProperties.Minio cfg = props.getStorage().getMinio();
        this.bucket = cfg.getBucket();
        this.client = MinioClient.builder()
                .endpoint(cfg.getEndpoint())
                .credentials(cfg.getAccessKey(), cfg.getSecretKey())
                .build();
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO bucket 不存在，已自动创建: {}", bucket);
            }
            log.info("MinIO 对象存储已启用（app.storage.type=minio）：endpoint={} bucket={}",
                    cfg.getEndpoint(), bucket);
        } catch (Exception e) {
            // 启动阶段连接失败要抛出来：配置错了就应该立刻失败，而不是等第一次上传才发现
            throw new IllegalStateException("MinIO 初始化失败，请检查 endpoint / accessKey / secretKey: "
                    + e.getMessage(), e);
        }
    }

    @Override
    public String save(String objectKey, InputStream in, long size, String contentType) {
        try {
            PutObjectArgs.Builder builder = PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(in, size, size < 0 ? 10L * 1024 * 1024 : -1);
            if (contentType != null && !contentType.isBlank()) {
                builder.contentType(contentType);
            }
            client.putObject(builder.build());
            log.debug("对象已上传: {}/{} ({} bytes)", bucket, objectKey, size);
            return objectKey;
        } catch (Exception e) {
            throw new BizException("对象存储上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] read(String objectKey) {
        try (InputStream in = openStream(objectKey)) {
            return in.readAllBytes();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("对象存储读取失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream openStream(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw BizException.notFound("对象不存在或读取失败: " + objectKey);
        }
    }

    /**
     * 下载到本地临时目录并返回路径（因为 Python 解析接口只接受文件路径）。
     *
     * <p>临时文件放在系统临时目录下、按 objectKey 的 hash 命名，避免中文路径问题
     * （AI 服务底层 FAISS 在 Windows 上用 ANSI 打开文件，中文路径会失败）。
     */
    @Override
    public String absolutePath(String objectKey) {
        try {
            Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"), "rag-platform-cache");
            Files.createDirectories(tmpDir);
            String safeName = Integer.toHexString(objectKey.hashCode())
                    + "_" + objectKey.replaceAll("[^A-Za-z0-9._-]", "_");
            Path target = tmpDir.resolve(safeName);
            if (!Files.exists(target)) {
                try (InputStream in = openStream(objectKey)) {
                    Files.copy(in, target);
                }
                log.debug("对象已缓存到本地临时文件用于解析: {} -> {}", objectKey, target);
            }
            return target.toString();
        } catch (Exception e) {
            throw new BizException("对象下载到本地失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return true;
        } catch (Exception e) {
            log.warn("对象删除失败 {}: {}", objectKey, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean exists(String objectKey) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String implementation() {
        return "minio";
    }

    /** 供测试/运维使用：把字节数组直接放进存储 */
    public String saveBytes(String objectKey, byte[] data, String contentType) {
        return save(objectKey, new ByteArrayInputStream(data), data.length, contentType);
    }
}
