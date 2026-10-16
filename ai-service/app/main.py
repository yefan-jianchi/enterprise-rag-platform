"""
企业级多模型 RAG 知识库与智能客服平台 —— AI 服务入口
====================================================
这个服务只干「重活」：文档解析、分块、向量化、索引、混合检索、重排、模型调用。
业务数据（租户/用户/权限/会话/审计）归 Java 后端管，两边职责清晰。

启动：
    python -m uvicorn app.main:app --host 0.0.0.0 --port 8001 --reload
或：
    python main.py

接口文档： http://localhost:8001/docs
"""
from __future__ import annotations

import json
import logging
import os
import time
import uuid
from typing import List, Optional

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse

from app import __version__
from app.config import get_settings
from app.core.chunker import chunk_document
from app.core.llm import get_llm, render_template
from app.core.parser import parse_document
from app.core.retrieval import get_service
from app.core.vector_store import FAISS_AVAILABLE
from app.integrations.langchain_bridge import (
    LANGCHAIN_AVAILABLE,
    LANGCHAIN_IMPORT_ERROR,
    answer_with_langchain,
    langchain_status,
)
from app.schemas import (
    ChunkOut,
    Citation,
    HealthResponse,
    IndexBuildRequest,
    IndexBuildResponse,
    ParseRequest,
    ParseResponse,
    RagAskRequest,
    RagAskResponse,
    RetrieveRequest,
    RetrieveResponse,
)

# ============================ 日志 ============================
settings = get_settings()
logging.basicConfig(
    level=getattr(logging, settings.log_level.upper(), logging.INFO),
    format="%(asctime)s | %(levelname)-7s | %(name)s | %(message)s",
)
logger = logging.getLogger("ai-service")

app = FastAPI(
    title="企业级 RAG 知识库 · AI 服务",
    description="文档解析 / 分块 / 向量化 / 混合检索(BM25+向量) / RRF 融合 / 重排 / 多模型适配",
    version=__version__,
)

# 前端（Vue3，另一个端口）需要跨域
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

UPLOAD_DIR = str(settings.data_path / "uploads")
os.makedirs(UPLOAD_DIR, exist_ok=True)


# ============================ 健康检查 ============================
@app.get("/health", response_model=HealthResponse, tags=["系统"])
def health() -> HealthResponse:
    """K8s / Docker healthcheck 用；也顺便暴露当前用的是哪个模型。"""
    return HealthResponse(
        status="ok",
        app=settings.app_name,
        version=__version__,
        embed_provider=settings.embed_provider,
        llm_provider=settings.llm_provider,
        faiss_available=FAISS_AVAILABLE,
        data_dir=str(settings.data_path),
        langchain=langchain_status(),
    )


# ============================ 文档解析 ============================
def _do_parse(path: str, file_name: str, req_chunk_size: Optional[int],
              req_overlap: Optional[int], doc_id: Optional[int]) -> ParseResponse:
    t0 = time.time()
    doc = parse_document(path)
    size = req_chunk_size or settings.chunk_size
    overlap = req_overlap if req_overlap is not None else settings.chunk_overlap
    chunks = chunk_document(doc, chunk_size=size, chunk_overlap=overlap,
                            min_chunk_size=settings.min_chunk_size)
    return ParseResponse(
        doc_id=doc_id,
        file_name=file_name,
        doc_type=doc.doc_type,
        page_count=doc.page_count,
        char_count=doc.char_count,
        chunk_count=len(chunks),
        chunks=[
            ChunkOut(
                chunk_index=c.chunk_index,
                content=c.content,
                token_count=c.token_count,
                page_no=c.page_no,
                char_start=c.char_start,
                char_end=c.char_end,
            )
            for c in chunks
        ],
        elapsed_ms=int((time.time() - t0) * 1000),
    )


@app.post("/api/v1/documents/parse", response_model=ParseResponse, tags=["文档中心"])
def parse_by_path(req: ParseRequest) -> ParseResponse:
    """解析服务端已有文件（Java 后端从 MinIO 下载后调用这个接口）。"""
    try:
        return _do_parse(req.file_path, os.path.basename(req.file_path),
                         req.chunk_size, req.chunk_overlap, req.doc_id)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except Exception as exc:
        logger.exception("解析失败")
        raise HTTPException(status_code=500, detail=f"解析失败: {exc}") from exc


@app.post("/api/v1/documents/upload", response_model=ParseResponse, tags=["文档中心"])
async def parse_upload(file: UploadFile = File(...)) -> ParseResponse:
    """直接上传文件解析（离线调试 / 独立演示用，不需要 Java 后端）。"""
    safe_name = os.path.basename(file.filename or "upload.bin")
    target = os.path.join(UPLOAD_DIR, f"{uuid.uuid4().hex[:8]}_{safe_name}")
    with open(target, "wb") as f:
        f.write(await file.read())
    try:
        return _do_parse(target, safe_name, None, None, None)
    except Exception as exc:
        logger.exception("上传解析失败")
        raise HTTPException(status_code=500, detail=f"解析失败: {exc}") from exc


# ============================ 索引 ============================
@app.post("/api/v1/index/build", response_model=IndexBuildResponse, tags=["RAG 引擎"])
def build_index(req: IndexBuildRequest) -> IndexBuildResponse:
    """建（或重建）某个知识库的向量索引 + BM25 索引。"""
    if not req.chunks:
        raise HTTPException(status_code=400, detail="chunks 不能为空")
    svc = get_service(req.kb_id)
    return svc.index_chunks(req.chunks, rebuild=req.rebuild)


@app.post("/api/v1/index/delete-doc", tags=["RAG 引擎"])
def delete_doc_index(kb_id: int, doc_id: int) -> dict:
    svc = get_service(kb_id)
    removed = svc.delete_doc(doc_id)
    return {"kb_id": kb_id, "doc_id": doc_id, "removed_vectors": removed}


@app.get("/api/v1/kb/{kb_id}/stats", tags=["RAG 引擎"])
def kb_stats(kb_id: int) -> dict:
    return get_service(kb_id).stats()


# ============================ 检索 ============================
@app.post("/api/v1/retrieve", response_model=RetrieveResponse, tags=["RAG 引擎"])
def retrieve(req: RetrieveRequest) -> RetrieveResponse:
    """
    混合检索。strategy 可选：
        VECTOR        仅向量
        BM25          仅关键词
        HYBRID_RRF    混合 + RRF 融合
        HYBRID_RERANK 混合 + RRF + 重排（推荐）
    """
    return get_service(req.kb_id).retrieve(req)


# ============================ RAG 问答 ============================
def _build_citations(results) -> List[Citation]:
    cites: List[Citation] = []
    for i, r in enumerate(results, start=1):
        snippet = r.content[:160].replace("\n", " ")
        cites.append(
            Citation(
                index=i,
                chunk_id=r.chunk_id,
                doc_id=r.doc_id,
                doc_name=r.doc_name or f"文档{r.doc_id}",
                page_no=r.page_no,
                chunk_index=r.chunk_index,
                score=r.score,
                snippet=snippet + ("..." if len(r.content) > 160 else ""),
            )
        )
    return cites


def _render_context(results) -> str:
    """把检索结果拼成带编号的上下文，编号要和引用 [1][2] 对得上。"""
    parts = []
    for i, r in enumerate(results, start=1):
        head = f"[{i}] {r.doc_name or ('文档' + str(r.doc_id))}"
        if r.page_no:
            head += f" 第{r.page_no}页"
        parts.append(f"{head}\n{r.content}")
    return "\n\n".join(parts)


@app.post("/api/v1/rag/ask", response_model=RagAskResponse, tags=["RAG 引擎"])
def rag_ask(req: RagAskRequest) -> RagAskResponse:
    """检索增强问答（一次性返回，非流式）。"""
    t0 = time.time()
    trace_id = uuid.uuid4().hex
    results = []
    if req.kb_id:
        rr = get_service(req.kb_id).retrieve(
            RetrieveRequest(kb_id=req.kb_id, query=req.question,
                            top_k=req.top_k, strategy=req.strategy)
        )
        results = rr.results

    context = _render_context(results) if results else ""
    template = "CS_REPLY" if req.system_prompt is None and req.kb_id is None else "RAG_QA"
    prompt = req.system_prompt or render_template(template, context=context or "（无检索结果）",
                                                  question=req.question)
    llm = get_llm(req.provider)
    res = llm.complete(
        [{"role": "system", "content": "你是严谨的企业知识库助手。"},
         {"role": "user", "content": prompt}],
        task="chat",
        **({"temperature": req.temperature} if req.temperature is not None else {}),
    )
    return RagAskResponse(
        answer=res.content,
        citations=_build_citations(results),
        provider=res.provider,
        model=res.model,
        prompt_tokens=res.prompt_tokens,
        completion_tokens=res.completion_tokens,
        total_tokens=res.total_tokens,
        elapsed_ms=int((time.time() - t0) * 1000),
        trace_id=trace_id,
    )


@app.post("/api/v1/rag/stream", tags=["RAG 引擎"])
async def rag_stream(req: RagAskRequest):
    """
    SSE 流式问答。
    事件顺序：
        event: meta       检索命中的引用（前端先渲染引用卡片）
        event: delta      正文增量
        event: done       结束，带 token 统计
    """
    trace_id = uuid.uuid4().hex
    t0 = time.time()

    results = []
    if req.kb_id:
        rr = get_service(req.kb_id).retrieve(
            RetrieveRequest(kb_id=req.kb_id, query=req.question,
                            top_k=req.top_k, strategy=req.strategy)
        )
        results = rr.results
    citations = _build_citations(results)
    context = _render_context(results) if results else "（无检索结果）"
    prompt = req.system_prompt or render_template("RAG_QA", context=context, question=req.question)
    llm = get_llm(req.provider)

    async def event_gen():
        answer_parts: list = []
        yield f"event: meta\ndata: {json.dumps({'traceId': trace_id, 'citations': [c.model_dump() for c in citations]}, ensure_ascii=False)}\n\n"
        try:
            async for piece in llm.astream(
                [{"role": "system", "content": "你是严谨的企业知识库助手。"},
                 {"role": "user", "content": prompt}],
                task="chat",
            ):
                answer_parts.append(piece)
                yield f"event: delta\ndata: {json.dumps({'content': piece}, ensure_ascii=False)}\n\n"
        except Exception as exc:  # 模型异常也要给前端一个明确收尾
            logger.exception("流式生成失败")
            yield f"event: error\ndata: {json.dumps({'message': str(exc)}, ensure_ascii=False)}\n\n"

        # done 事件带上 token 统计：Java 后端要用它写 ai_call_log（成本审计）。
        # 说明：流式响应默认拿不到服务商的精确 usage，这里按字符数估算
        #（中文约 1 字 ≈ 1 token，英文约 4 字符 ≈ 1 token，统一按 len/2 近似），
        # 并在响应里标 estimated=True，避免下游把估算值当账单。
        answer = "".join(answer_parts)
        prompt_tokens = max(1, (len(prompt) + len(req.question)) // 2)
        completion_tokens = max(1, len(answer) // 2)
        yield (
            "event: done\ndata: "
            + json.dumps(
                {
                    "elapsedMs": int((time.time() - t0) * 1000),
                    "provider": llm.provider,
                    "model": llm.model,
                    "promptTokens": prompt_tokens,
                    "completionTokens": completion_tokens,
                    "totalTokens": prompt_tokens + completion_tokens,
                    "estimated": True,
                },
                ensure_ascii=False,
            )
            + "\n\n"
        )

    return StreamingResponse(
        event_gen(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no", "Connection": "keep-alive"},
    )


# ============================ LangChain 编排（同一套索引，另一条实现路径）============================
@app.post("/api/v1/langchain/rag/ask", response_model=RagAskResponse, tags=["LangChain 集成"])
def langchain_rag_ask(req: RagAskRequest) -> RagAskResponse:
    """
    用 LangChain 的 LCEL 链完成同样的 RAG：

        retriever(自研混合检索) | format_docs | ChatPromptTemplate | ChatModel | StrOutputParser

    与 /api/v1/rag/ask **共用同一套索引与检索器**，所以结果与引用结构一致，
    区别只在「编排方式」：手写链路对 RRF/阈值/归一化的控制更精细，
    LangChain 版接入生态组件（Agent、Memory、Callback 观测）更快。
    """
    if not LANGCHAIN_AVAILABLE:
        raise HTTPException(status_code=501, detail=f"LangChain 不可用: {LANGCHAIN_IMPORT_ERROR}")

    t0 = time.time()
    trace_id = uuid.uuid4().hex
    try:
        result = answer_with_langchain(
            req.question, kb_id=req.kb_id, top_k=req.top_k,
            strategy=req.strategy, provider=req.provider,
        )
    except Exception as exc:
        logger.exception("LangChain 链路失败")
        raise HTTPException(status_code=500, detail=f"LangChain 链路失败: {exc}") from exc

    citations: List[Citation] = []
    for i, d in enumerate(result["documents"], start=1):
        md = d.metadata
        snippet = d.page_content[:160].replace("\n", " ")
        citations.append(
            Citation(
                index=i,
                chunk_id=int(md.get("chunk_id", 0)),
                doc_id=int(md.get("doc_id", 0)),
                doc_name=str(md.get("doc_name", "")),
                page_no=md.get("page_no"),
                chunk_index=int(md.get("chunk_index", 0)),
                score=float(md.get("score", 0.0)),
                snippet=snippet + ("..." if len(d.page_content) > 160 else ""),
            )
        )

    return RagAskResponse(
        answer=result["answer"],
        citations=citations,
        provider="langchain",
        model=("openai-compat" if settings.llm_provider != "mock" else "mock-chat"),
        elapsed_ms=int((time.time() - t0) * 1000),
        trace_id=trace_id,
    )


if __name__ == "__main__":  # pragma: no cover
    import uvicorn

    print("=" * 64)
    print("  企业级 RAG 知识库 · AI 服务")
    print(f"  向量提供方 : {settings.embed_provider} ({settings.embed_model})")
    print(f"  模型提供方 : {settings.llm_provider}")
    print(f"  索引目录   : {settings.index_path}")
    print(f"  接口文档   : http://127.0.0.1:{settings.port}/docs")
    print("=" * 64)
    uvicorn.run(app, host=settings.host, port=settings.port, log_level=settings.log_level.lower())
