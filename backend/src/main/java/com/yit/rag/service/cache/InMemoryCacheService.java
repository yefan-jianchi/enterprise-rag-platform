package com.yit.rag.service.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yit.rag.config.AppProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 内存缓存实现（本机默认）。
 *
 * <p>这个类解决什么问题：让项目在没有 Redis 的机器上也能完整跑起来，
 * 同时保持与 Redis 实现完全一样的接口语义，切换只改一行配置。
 *
 * <p>实现要点（都是面试会被追问的点）：
 * <ol>
 *   <li><b>过期怎么做</b>：每个 key 存「值 + 绝对过期时间」。读取时惰性判断，
 *       同时后台每 60 秒扫一遍清理已过期的 key，避免死 key 占内存。</li>
 *   <li><b>为什么不用 Guava/Caffeine</b>：Caffeine 当然更强（W-TinyLFU 淘汰），
 *       但引入额外依赖只为了一个「本机兜底」，不如自己写 100 行讲得清楚。
 *       真要上生产，把这里换成 Caffeine 或直接切 Redis 即可。</li>
 *   <li><b>容量保护</b>：{@code memoryMaxSize} 到顶后按写入顺序淘汰最早的 key（近似 FIFO），
 *       防止无界增长把堆撑爆。</li>
 *   <li><b>多实例语义</b>：这是单机缓存，多实例部署时各自的缓存不共享 ——
 *       所以生产环境必须切 redis，这个限制写在配置注释里。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.cache.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryCacheService implements CacheService {

    private final AppProperties props;
    private final ObjectMapper objectMapper;

    /** key → 缓存条目 */
    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    /** 后台清理线程（守护线程，不阻塞 JVM 退出） */
    private ScheduledExecutorService cleaner;

    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();

    @PostConstruct
    void init() {
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "in-memory-cache-cleaner");
            t.setDaemon(true);
            return t;
        });
        this.cleaner.scheduleWithFixedDelay(this::purgeExpired, 60, 60, TimeUnit.SECONDS);
        log.info("内存缓存已启用（app.cache.type=memory）：单机语义，多实例部署请切换 redis；"
                + "容量上限 {} 条", props.getCache().getMemoryMaxSize());
    }

    @PreDestroy
    void shutdown() {
        if (cleaner != null) {
            cleaner.shutdownNow();
        }
        store.clear();
    }

    // ================================================================= 写入

    @Override
    public void put(String key, Object value) {
        put(key, value, Duration.ofSeconds(props.getCache().getDefaultTtlSeconds()));
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        if (key == null || value == null) {
            return;
        }
        evictIfFull();
        store.put(key, new Entry(value, expireAt(ttl)));
    }

    // ================================================================= 读取

    @Override
    public <T> T get(String key, Class<T> type) {
        Entry e = store.get(key);
        if (e == null) {
            missCount.incrementAndGet();
            return null;
        }
        if (e.isExpired()) {
            store.remove(key);
            missCount.incrementAndGet();
            return null;
        }
        hitCount.incrementAndGet();
        return convert(e.value, type);
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
        Entry e = store.get(key);
        if (e == null) {
            return false;
        }
        if (e.isExpired()) {
            store.remove(key);
            return false;
        }
        return true;
    }

    // ================================================================= 删除

    @Override
    public void evict(String key) {
        if (key != null) {
            store.remove(key);
        }
    }

    @Override
    public long evictByPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return 0;
        }
        long removed = 0;
        Iterator<String> it = store.keySet().iterator();
        while (it.hasNext()) {
            if (it.next().startsWith(prefix)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("内存缓存按前缀清理 {} 个 key: {}", removed, prefix);
        }
        return removed;
    }

    // ================================================================= 计数

    @Override
    public long increment(String key, long delta, Duration ttl) {
        Entry e = store.get(key);
        long next;
        if (e == null || e.isExpired()) {
            next = delta;
            store.put(key, new Entry(next, expireAt(ttl)));
        } else {
            // 注意：这里用 synchronized 保证「读-改-写」原子，避免并发自增丢更新
            synchronized (e) {
                long cur = ((Number) e.value).longValue();
                next = cur + delta;
                e.value = next;
            }
        }
        return next;
    }

    @Override
    public String implementation() {
        return "memory";
    }

    /** 命中率，只有内存实现能直接算出来，暴露给日志/监控很有用 */
    public double hitRate() {
        long h = hitCount.get();
        long m = missCount.get();
        return (h + m) == 0 ? 0d : (double) h / (h + m);
    }

    // ================================================================= 内部

    private long expireAt(Duration ttl) {
        Duration d = (ttl == null || ttl.isZero() || ttl.isNegative())
                ? Duration.ofSeconds(props.getCache().getDefaultTtlSeconds())
                : ttl;
        return System.currentTimeMillis() + d.toMillis();
    }

    private void evictIfFull() {
        int max = props.getCache().getMemoryMaxSize();
        if (max > 0 && store.size() >= max) {
            // 近似 FIFO：清掉一部分，避免每次写入都触发淘汰
            int toRemove = Math.max(1, max / 20);
            Iterator<String> it = store.keySet().iterator();
            while (it.hasNext() && toRemove-- > 0) {
                it.next();
                it.remove();
            }
            log.warn("内存缓存已达上限 {}，淘汰了 {} 个 key（建议切换 redis）", max, max / 20);
        }
    }

    private void purgeExpired() {
        try {
            int before = store.size();
            store.entrySet().removeIf(en -> en.getValue().isExpired());
            int removed = before - store.size();
            if (removed > 0) {
                log.debug("内存缓存定期清理：移除 {} 个过期 key，当前 {} 个", removed, store.size());
            }
        } catch (Exception ex) {
            log.warn("内存缓存清理任务异常: {}", ex.getMessage());
        }
    }

    /**
     * 类型转换：缓存里存的是原始对象（内存实现不需要序列化）。
     * 如果类型不匹配（例如换了实现导致存的类型变了），返回 null 而不是抛异常 —— 缓存不能影响主流程。
     */
    @SuppressWarnings("unchecked")
    private <T> T convert(Object value, Class<T> type) {
        if (type == null || type.isInstance(value)) {
            return (T) value;
        }
        // 数字、字符串这类常见转换兜个底
        try {
            if (type == String.class) {
                return (T) String.valueOf(value);
            }
            if (type == Long.class && value instanceof Number n) {
                return (T) Long.valueOf(n.longValue());
            }
            if (type == Integer.class && value instanceof Number n) {
                return (T) Integer.valueOf(n.intValue());
            }
            return objectMapper.convertValue(value, type);
        } catch (Exception e) {
            log.debug("内存缓存类型转换失败 key 类型={}: {}", type, e.getMessage());
            return null;
        }
    }

    /** 缓存条目：值 + 绝对过期时间戳 */
    private static final class Entry {
        private volatile Object value;
        private final long expireAt;

        Entry(Object value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }
    }
}
