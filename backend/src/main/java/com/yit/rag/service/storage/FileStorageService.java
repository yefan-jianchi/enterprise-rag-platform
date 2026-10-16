package com.yit.rag.service.storage;

import java.io.InputStream;

/**
 * 对象存储抽象。
 *
 * <p>这个类解决什么问题：文档原始文件要持久化，生产上用 MinIO（S3 兼容），
 * 但本机开发没有 MinIO —— 总不能为了跑个 demo 先装一套对象存储。
 *
 * <p>所以抽出接口，靠配置切换：
 * <pre>
 *   app.storage.type = local  →  LocalFileStorageService（落本地磁盘，零依赖）
 *   app.storage.type = minio  →  MinioStorageService（对象存储，多实例共享）
 * </pre>
 *
 * <p>关键设计：所有实现都必须提供「能拿到绝对路径」的能力（{@link #absolutePath}）。
 * 原因是文档解析走的是 Python AI 服务的 {@code /api/v1/documents/parse}，
 * 它接收的是服务端文件系统路径而不是文件流。MinIO 实现的做法是「先下载到本地临时目录再解析」，
 * 这个语义差异必须由实现自己消化，不能泄漏到业务层。
 */
public interface FileStorageService {

    /**
     * 保存文件。
     *
     * @param objectKey 对象键（业务侧生成，形如 tenant/1/kb/2/xxx.md），实现负责把它映射成
     *                  本地路径或存储对象名
     * @param in        输入流
     * @param size      字节数（-1 表示未知）
     * @param contentType MIME 类型，可为 null
     * @return 实际存储的实际标识（local 返回相对 key；minio 返回 object name）
     */
    String save(String objectKey, InputStream in, long size, String contentType);

    /**
     * 读取文件内容（小文件直接返回字节数组，用于下载接口）。
     */
    byte[] read(String objectKey);

    /**
     * 取文件的可读流（大文件场景）。
     */
    InputStream openStream(String objectKey);

    /**
     * 拿到「本地可读的绝对路径」。
     *
     * <p>这是给 AI 服务的文档解析接口用的：Python 侧要的是路径，不是流。
     * local 实现直接返回磁盘路径；minio 实现会把对象下载到临时目录后返回临时路径。
     */
    String absolutePath(String objectKey);

    /**
     * 删除文件。返回是否确实删掉了（false 表示本来就不存在）。
     */
    boolean delete(String objectKey);

    /**
     * 判断文件是否存在。
     */
    boolean exists(String objectKey);

    /**
     * 当前实现名，用于启动日志与前端展示（让演示者一眼看出文件存哪了）。
     */
    String implementation();
}
