package com.yit.rag.service.cache;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 缓存服务抽象。
 *
 * <p>这个类解决什么问题：本机开发环境没有 Redis，但生产环境必须有。
 * 如果代码里直接注入 {@code StringRedisTemplate}，本机就跑不起来，
 * 「拉下来就能跑」这个体验就没了。
 *
 * <p>所以这里抽出接口，靠配置切换实现：
 * <pre>
 *   app.cache.type = memory  →  InMemoryCacheService（进程内 Map + TTL，零依赖）
 *   app.cache.type = redis   →  RedisCacheService（真 Redis，多实例共享）
 * </pre>
 * 业务代码只依赖这个接口，切换实现不需要改一行业务逻辑。
 *
 * <p>典型用途：
 * <ul>
 *   <li>知识库列表、模型提供方列表等「读多写少」的数据</li>
 *   <li>{@link #getOrLoad} 做缓存穿透保护</li>
 *   <li>登录失败次数、接口限流计数</li>
 * </ul>
 */
public interface CacheService {

    /**
     * 写入缓存（使用配置的默认 TTL）。
     */
    void put(String key, Object value);

    /**
     * 写入缓存并指定 TTL。
     */
    void put(String key, Object value, Duration ttl);

    /**
     * 读取缓存，未命中或已过期返回 null。
     */
    <T> T get(String key, Class<T> type);

    /**
     * 删除单个 key。
     */
    void evict(String key);

    /**
     * 按前缀批量删除（例如某个知识库变更后清掉所有相关缓存）。
     */
    long evictByPrefix(String prefix);

    /**
     * 判断 key 是否存在（未过期才算存在）。
     */
    boolean exists(String key);

    /**
     * 缓存旁路（Cache-Aside）读：命中直接返回，未命中则调用 loader 并回填。
     *
     * <p>这个方法的价值在于把「查缓存 → 未命中查库 → 回填」这个容易写漏的三步
     * 收敛到一个地方。注意它不做并发去重（不用分布式锁），
     * 因为击穿场景下多查一次库的代价远小于引入锁的复杂度。
     */
    <T> T getOrLoad(String key, Class<T> type, Duration ttl, Supplier<T> loader);

    /**
     * 自增计数（用于限流、失败次数统计）。内存实现是单机语义，
     * Redis 实现是分布式语义 —— 这个差异要在文档里写清楚，别让人误解。
     */
    long increment(String key, long delta, Duration ttl);

    /**
     * 当前实现的名字，用于启动日志与 /health 展示，方便一眼看出跑的是哪套。
     */
    String implementation();
}
