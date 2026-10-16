package com.yit.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池配置。
 *
 * <p>这个类解决什么问题：文档处理（解析 → 分块 → 向量化 → 建索引）是重活，
 * 一份几十页的 PDF 可能要几十秒。如果在上传接口里同步跑，
 * 用户会看到一个转了半分钟的圈，还容易触发网关超时。
 *
 * <p>所以上传接口只做「落库 + 落文件 + 返回」，处理逻辑丢到独立线程池；
 * 前端通过 {@code /api/doc/list} 轮询 document.status 看进度：
 * {@code PENDING → PARSING → CHUNKING → EMBEDDING → INDEXED/FAILED}。
 *
 * <p>为什么不直接用 {@code @Async} 的默认线程池（SimpleAsyncTaskExecutor）：
 * 默认实现<b>不复用线程</b>，每个任务新建一个线程。文档处理是耗时任务，
 * 并发几个大文档就会创建一堆线程，把内存吃光。
 *
 * <p>线程池参数是怎么定的（面试会问）：
 * <ul>
 *   <li><b>核心 2 / 最大 4</b>：文档处理是 CPU + 网络混合型任务，
 *       而且后端只是调 AI 服务的 HTTP 接口，本身不吃 CPU。
 *       并发太高会把 AI 服务压垮（它的 FAISS 索引写操作还要加锁），
 *       所以刻意压小，让任务排队而不是把下游打挂。</li>
 *   <li><b>队列 200</b>：批量上传 100 个文档时任务不丢，排队执行。</li>
 *   <li><b>CallerRunsPolicy</b>：队列也满时，由提交任务的线程（也就是 Tomcat 线程）
 *       自己执行。这是一种「背压」策略 —— 宁可让上传接口慢一点，
 *       也不能静默丢弃任务（丢任务意味着文档永远停在 PENDING，用户完全不知道发生了什么）。</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    /** 文档处理专用线程池 */
    @Bean("documentExecutor")
    public Executor documentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(120);
        executor.setThreadNamePrefix("doc-process-");
        // 队列满时由调用线程执行，保证任务不丢（宁可慢，不可静默失败）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅关闭：等在跑的文档处理完再退出，避免留下「处理到一半」的脏状态
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        log.info("文档处理线程池已初始化：core=2 max=4 queue=200 拒绝策略=CallerRunsPolicy");
        return executor;
    }

    /**
     * SSE 流式请求专用线程池。
     *
     * <p>为什么要单独一个池，而不是直接用 Tomcat 的请求线程：
     * <ul>
     *   <li>SSE 是长连接，一个流可能占用线程几十秒。如果直接在 Tomcat 线程里跑，
     *       并发 200 个流就把 Tomcat 的 200 个工作线程全占了，
     *       其他接口（登录、列表）全部排队 —— 这是非常典型的雪崩场景。</li>
     *   <li>独立线程池可以针对性设置：并发上限、队列、以及「队列满时快速失败」
     *       （AbortPolicy）。流式请求失败可以立刻告诉前端「服务繁忙」，
     *       总比让所有接口一起卡死好。</li>
     * </ul>
     *
     * <p>注意：用 callbacks 注册完成/超时回调把 emitter 结束掉，
     * 避免线程一直挂着等客户端。
     */
    @Bean("sseExecutor")
    public Executor sseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 流式任务绝大部分时间在等网络 IO（等模型吐字），所以线程数可以给得比 CPU 核数多
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("sse-");
        // 队列满时直接拒绝：让前端立刻收到「服务繁忙」，而不是所有接口一起变慢
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        log.info("SSE 线程池已初始化：core=8 max=32 queue=100 拒绝策略=AbortPolicy");
        return executor;
    }
}
