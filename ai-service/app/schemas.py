"""
接口数据模型
============
所有对外 HTTP 接口的请求/响应都用 Pydantic 定义，
好处：自动生成 OpenAPI 文档、自动校验、类型安全。
"""
from __future__ import annotations

from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


# ============================ 文档解析 ============================
class ParseRequest(BaseModel):
    """解析一份文档：给文件路径，返回带页码的纯文本与分块结果。"""

    file_path: str = Field(..., description="服务端可见的文件绝对路径")
    doc_id: Optional[int] = Field(None, description="业务侧文档 ID，会透传回结果")
    chunk_size: Optional[int] = Field(None, description="覆盖默认分块长度")
    chunk_overlap: Optional[int] = Field(None, description="覆盖默认重叠长度")


class ChunkOut(BaseModel):
    """一个分块，也是 RAG 检索的最小单元。"""

    chunk_index: int
    content: str
    token_count: int
    page_no: Optional[int] = None       # 页码，用于引用溯源
    char_start: Optional[int] = None    # 在原文中的起始偏移
    char_end: Optional[int] = None


class ParseResponse(BaseModel):
    doc_id: Optional[int] = None
    file_name: str
    doc_type: str
    page_count: int
    char_count: int
    chunk_count: int
    chunks: List[ChunkOut]
    elapsed_ms: int


# ============================ 建索引 ============================
class IndexChunkIn(BaseModel):
    """建索引时传入的分块（业务库里的 kb_chunk.id 作为 chunk_id）。"""

    chunk_id: int
    doc_id: int
    doc_name: str = ""
    chunk_index: int = 0
    content: str
    page_no: Optional[int] = None


class IndexBuildRequest(BaseModel):
    kb_id: int
    chunks: List[IndexChunkIn]
    rebuild: bool = Field(True, description="True=重建整个知识库索引；False=增量追加")


class IndexBuildResponse(BaseModel):
    kb_id: int
    indexed_chunks: int
    total_chunks: int
    embed_dim: int
    embed_provider: str
    elapsed_ms: int


# ============================ 检索 ============================
class RetrieveRequest(BaseModel):
    kb_id: int
    query: str
    top_k: Optional[int] = None
    candidate_k: Optional[int] = None
    strategy: str = Field(
        "HYBRID_RRF",
        description="VECTOR（纯向量）/ BM25（纯关键词）/ HYBRID_RRF（混合+RRF）/ HYBRID_RERANK（混合+RFF+重排）",
    )
    enable_rewrite: Optional[bool] = None
    enable_hyde: Optional[bool] = None


class RetrievedChunk(BaseModel):
    chunk_id: int
    doc_id: int
    doc_name: str = ""
    chunk_index: int = 0
    content: str
    page_no: Optional[int] = None
    score: float                       # 最终排序分
    vector_score: Optional[float] = None
    bm25_score: Optional[float] = None
    rrf_score: Optional[float] = None
    rerank_score: Optional[float] = None
    rank: int = 0                      # 最终名次，从 1 开始


class RetrieveResponse(BaseModel):
    kb_id: int
    query: str
    rewritten_queries: List[str] = []
    hyde_document: Optional[str] = None
    strategy: str
    total_candidates: int
    results: List[RetrievedChunk]
    elapsed_ms: int


# ============================ 完整 RAG 问答 ============================
class RagAskRequest(BaseModel):
    kb_id: Optional[int] = Field(None, description="为空表示不检索，纯模型对话")
    question: str
    top_k: Optional[int] = None
    strategy: str = "HYBRID_RERANK"
    provider: Optional[str] = Field(None, description="覆盖默认模型提供方")
    system_prompt: Optional[str] = None
    temperature: Optional[float] = None


class Citation(BaseModel):
    """引用溯源：回答里的 [1] 对应哪个文档、哪一页、哪一段。"""

    index: int
    chunk_id: int
    doc_id: int
    doc_name: str
    page_no: Optional[int] = None
    chunk_index: int
    score: float
    snippet: str


class RagAskResponse(BaseModel):
    answer: str
    citations: List[Citation]
    provider: str
    model: str
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0
    elapsed_ms: int
    trace_id: str


# ============================ 通用 ============================
class HealthResponse(BaseModel):
    status: str
    app: str
    version: str
    embed_provider: str
    llm_provider: str
    faiss_available: bool
    data_dir: str
    langchain: Dict[str, Any] = Field(default_factory=dict, description="LangChain 可用性")


class ErrorResponse(BaseModel):
    error: str
    detail: Optional[Dict[str, Any]] = None
