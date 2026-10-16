package com.yit.rag.api;

import com.yit.rag.api.dto.kb.KbDtos;
import com.yit.rag.common.R;
import com.yit.rag.service.kb.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档接口。
 *
 * <p>这个类解决什么问题：文档的上传、列表、重建索引、删除、查看分块。
 *
 * <p>上传接口是异步的：返回时文档状态是 {@code PENDING}，
 * 前端需要轮询 {@code GET /api/doc/list?kbId=} 看状态变化：
 * {@code PENDING → PARSING → CHUNKING → EMBEDDING → INDEXED}（失败为 FAILED，带 errorMsg）。
 * 这样做的好处是上传接口可以秒回，大文件也不会超时。
 */
@Slf4j
@RestController
@RequestMapping("/api/doc")
@RequiredArgsConstructor
@Tag(name = "文档", description = "文档上传、处理状态、分块查看")
public class DocumentController {

    private final DocumentService documentService;

    /**
     * 上传文档（multipart：file + kbId）。
     *
     * <p>注意参数用 {@code @RequestParam} 而不是 {@code @RequestPart}：
     * 前端用 FormData 直接把 file 和 kbId 平铺进去，这是最常见的写法。
     */
    @PostMapping("/upload")
    @PreAuthorize("hasAuthority('kb:doc:upload')")
    @Operation(summary = "上传文档", description = "异步处理：返回 PENDING，前端轮询列表看状态")
    public R<KbDtos.DocListItem> upload(@RequestParam("file") MultipartFile file,
                                        @RequestParam("kbId") Long kbId) {
        log.info("收到文档上传请求: kbId={} fileName={} size={}B",
                kbId, file.getOriginalFilename(), file.getSize());
        return R.ok(documentService.upload(kbId, file));
    }

    /** 某知识库下的文档列表（前端轮询状态就靠它） */
    @GetMapping("/list")
    @PreAuthorize("hasAuthority('kb:doc:view')")
    @Operation(summary = "文档列表")
    public R<List<KbDtos.DocListItem>> list(@RequestParam("kbId") Long kbId) {
        return R.ok(documentService.listByKb(kbId));
    }

    /** 单个文档详情 */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('kb:doc:view')")
    @Operation(summary = "文档详情")
    public R<KbDtos.DocListItem> get(@PathVariable("id") Long id) {
        return R.ok(documentService.get(id));
    }

    /** 重建索引（换 embedding 模型或调了分块参数后用） */
    @PostMapping("/{id}/reindex")
    @PreAuthorize("hasAuthority('kb:doc:reindex')")
    @Operation(summary = "重建索引")
    public R<KbDtos.DocListItem> reindex(@PathVariable("id") Long id) {
        return R.ok(documentService.reindex(id));
    }

    /** 删除文档（连带分块与向量） */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('kb:doc:delete')")
    @Operation(summary = "删除文档")
    public R<Void> delete(@PathVariable("id") Long id) {
        documentService.delete(id);
        return R.ok();
    }

    /** 查看文档的分块（用于验证分块质量与引用溯源） */
    @GetMapping("/{id}/chunks")
    @PreAuthorize("hasAuthority('kb:doc:view')")
    @Operation(summary = "查看分块")
    public R<List<KbDtos.ChunkItem>> chunks(@PathVariable("id") Long id) {
        return R.ok(documentService.chunks(id));
    }
}
