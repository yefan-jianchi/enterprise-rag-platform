package com.yit.rag.service.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yit.rag.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Redis 缓存实现（生产 / Docker 环境使用）。
 *
 * <p>这个类解决什么问题：内存缓存是「单机语义」，多实例部署时各进程缓存不一致，
 * 而且重启即失效。生产必须换成 Redis：
 * <pre>
 *   app.cache.type = redis
 *   spring.data.redis.host / port（或用环境变量 APP_REDIS_HOST）
 * </pre>
 *
 * <p>实现要点：
 * <ol>
 *   <li><b>序列化选择</b>：用 {@code StringRedisTemplate} + JSON 字符串，
 *       而不是默认的 JDK 序列化。原因：JDK 序列化在 Redis 里是二进制乱码，
 *       无法用 redis-cli 排查问题，而且跨语言/跨版本不兼容。</li>
 *   <li><b>类型还原</b>：JSON 里带上 {@code @class} 字段记录原始类型，
 *       反序列化时按需转成调用方期望的类型（简单类型直接返回，复杂类型走 convertValue）。</li>
 *   <li><b>前缀清理</b>：用 {@code SCAN} 游标而不是 {@code KEYS}。
 *       KEYS 在大 key 空间下会阻塞 Redis 主线程，生产事故高发，必须避免。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.cache.type", havingValue = "redis")
public class RedisCacheService implements CacheService {

    private static final String TYPE_FIELD = "@class";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AppProperties props;

    @Override
    public void put(String key, Object value) {
        put(key, value, Duration.ofSeconds(props.getCache().getDefaultTtlSeconds()));
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        if (key == null || value == null) {
            return;
        }
        try {
            Duration d = (ttl == null || ttl.isZero() || ttl.isNegative())
                    ? Duration.ofSeconds(props.getCache().getDefaultTtlSeconds())
                    : ttl;
            redis.opsForValue().set(key, toJson(value), d);
        } catch (Exception e) {
            // 缓存写入失败绝不能影响业务：打日志即可
            log.warn("Redis 写入失败 key={}: {}", key, e.getMessage());
        }
    }

    @Override
    public <T> T get(String key, Class<T> type) {
        if (key == null) {
            return null;
        }
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? null : fromJson(json, type);
        } catch (Exception e) {
            log.warn("Redis 读取失败 key={}: {}", key, e.getMessage());
            return null;
        }
    }

    @Override
    public <T> T getOrLoad(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        T cached = get(key, type);
        if (cached != null) {
            return cached;
        }
        T loaded = loader.get();
        if (loaded != null) {
            put(key, loaded, ttl);
        }
        return loaded;
    }

    @Override
    public boolean exists(String key) {
        try {
            return key != null && Boolean.TRUE.equals(redis.hasKey(key));
        } catch (Exception e) {
            log.warn("Redis exists 失败 key={}: {}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public void evict(String key) {
        if (key == null) {
            return;
        }
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("Redis 删除失败 key={}: {}", key, e.getMessage());
        }
    }

    @Override
    public long evictByPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return 0;
        }
        long removed = 0;
        try {
            // 用 SCAN 而不是 KEYS：KEYS 会阻塞 Redis 主线程
            ScanOptions options = ScanOptions.scanOptions().match(prefix + "*").count(500).build();
            try (Cursor<String> cursor = redis.scan(options)) {
                while (cursor.hasNext()) {
                    String k = cursor.next();
                    redis.delete(k);
                    removed++;
                }
            }
        } catch (Exception e) {
            log.warn("Redis 按前缀删除失败 prefix={}: {}", prefix, e.getMessage());
        }
        log.debug("Redis 按前缀清理 {} 个 key: {}", removed, prefix);
        return removed;
    }

    @Override
    public long increment(String key, long delta, Duration ttl) {
        try {
            Long v = redis.opsForValue().increment(key, delta);
            if (v != null && v == delta) {
                // 第一次写入时设置过期时间，避免 key 永不过期
                redis.expire(key, ttl == null ? Duration.ofSeconds(props.getCache().getDefaultTtlSeconds()) : ttl);
            }
            return v == null ? delta : v;
        } catch (Exception e) {
            log.warn("Redis 自增失败 key={}: {}", key, e.getMessage());
            return delta;
        }
    }

    @Override
    public String implementation() {
        return "redis";
    }

    // ================================================================= 内部

    /** 序列化成带类型信息的 JSON，便于反序列化时还原真实类型 */
    private String toJson(Object value) {
        try {
            var node = objectMapper.valueToTree(value);
            if (node.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) node)
                        .put(TYPE_FIELD, value.getClass().getName());
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("缓存序列化失败: " + e.getMessage(), e);
        }
    }

    /** 按调用方期望的类型还原；JSON 里的 @class 只作为参考（防止因类改动而反序列化失败） */
    private <T> T fromJson(String json, Class<T> type) throws Exception {
        var node = objectMapper.readTree(json);
        if (node.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove(TYPE_FIELD);
        }
        if (type == null || type == Object.class) {
            return (T) objectMapper.treeToValue(node, Object.class);
        }
        return objectMapper.treeToValue(node, type);
    }

    /** 供监控使用：Redis 里当前缓存的 key 数量（SCAN 统计，避免 KEYS） */
    public long approximateKeyCount(String prefix) {
        long count = 0;
        try (Cursor<String> cursor = redis.scan(
                ScanOptions.scanOptions().match((prefix == null ? "" : prefix) + "*").count(500).build())) {
            while (cursor.hasNext()) {
                cursor.next();
                count++;
            }
        } catch (Exception e) {
            log.warn("统计 Redis key 数量失败: {}", e.getMessage());
        }
        return count;
    }
}
