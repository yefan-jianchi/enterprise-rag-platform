"""
向量化（Embedding）
===================
设计要点：**提供方无关**。上层只认识 Embedder 抽象，不关心底层是 OpenAI、
通义还是本地哈希向量。这样切换模型不用改一行检索代码。

两种实现：
    1. HashEmbedder  —— 本地确定性向量，无需 API Key，离线可跑
                        原理：jieba 分词 + 字符二元组 → 哈希到固定维度 → L2 归一化
                        用 crc32 而不是 Python 内置 hash()，因为内置 hash 每个进程
                        加盐不同，建索引和查询必须跨进程一致。
    2. OpenAICompatEmbedder —— 走 OpenAI 兼容的 /embeddings 接口（DeepSeek/通义/OpenAI 通用）
"""
from __future__ import annotations

import logging
import zlib
from abc import ABC, abstractmethod
from typing import List, Optional, Sequence

import numpy as np

from app.config import get_settings
from app.core.tokenizer import tokenize_for_index

logger = logging.getLogger(__name__)


class BaseEmbedder(ABC):
    """向量化抽象基类。"""

    provider: str = "base"
    model: str = ""
    dim: int = 0

    @abstractmethod
    def embed_documents(self, texts: Sequence[str]) -> np.ndarray:
        """批量把文本转成向量，返回 shape=(n, dim) 的 float32 矩阵（已 L2 归一化）。"""

    def embed_query(self, text: str) -> np.ndarray:
        """单条查询向量，默认复用批量接口。"""
        return self.embed_documents([text])[0]

    @staticmethod
    def _l2_normalize(mat: np.ndarray) -> np.ndarray:
        """L2 归一化：归一化后内积 == 余弦相似度，可直接用 IndexFlatIP。"""
        norms = np.linalg.norm(mat, axis=1, keepdims=True)
        norms[norms == 0] = 1.0
        return (mat / norms).astype("float32")


class HashEmbedder(BaseEmbedder):
    """
    本地哈希向量化（Mock）。
    ============================
    为什么 RAG 项目需要一个「假」向量器？
      * 没有 API Key、断网、CI 环境都能把整条链路跑通并写单元测试
      * 单元测试不该依赖外部服务，也不该花钱
      * 面试时可以讲清楚「词哈希 + 子线性 TF 加权 + L2 归一化」的原理
    它当然不如真实 Embedding 懂语义，但保留了**词面重叠**这一基本信号，
    配合 BM25 混合检索，演示效果完全够用。
    """

    provider = "mock"

    def __init__(self, dim: int = 384, model: str = "mock-embed-384") -> None:
        self.dim = dim
        self.model = model

    @staticmethod
    def _bucket(token: str, dim: int) -> int:
        # crc32 是确定性的，跨进程/跨机器一致
        return zlib.crc32(token.encode("utf-8")) % dim

    def _embed_one(self, text: str) -> np.ndarray:
        vec = np.zeros(self.dim, dtype="float32")
        tokens = tokenize_for_index(text)
        if not tokens:
            return vec
        # 统计词频
        freq: dict[str, int] = {}
        for t in tokens:
            freq[t] = freq.get(t, 0) + 1
        for token, cnt in freq.items():
            idx = self._bucket(token, self.dim)
            # 子线性 TF：1 + log(tf)，抑制高频词主导
            vec[idx] += 1.0 + np.log(cnt)
        return vec

    def embed_documents(self, texts: Sequence[str]) -> np.ndarray:
        if not texts:
            return np.zeros((0, self.dim), dtype="float32")
        mat = np.vstack([self._embed_one(t) for t in texts])
        return self._l2_normalize(mat)


class OpenAICompatEmbedder(BaseEmbedder):
    """
    走 OpenAI 兼容协议的向量化（DeepSeek / 通义 / OpenAI 都支持）。
    注意：不同厂商向量维度不同（通义 v3=1024，OpenAI small=1536），
    所以换模型必须重建索引，代码里用 embed_dim 做一致性校验。
    """

    provider = "openai-compat"

    def __init__(self, base_url: str, api_key: str, model: str, dim: int, timeout: int = 60) -> None:
        if not api_key:
            raise ValueError(f"{model} 需要 API Key，请在 .env 里配置")
        import httpx

        self.model = model
        self.dim = dim
        self._client = httpx.Client(
            base_url=base_url.rstrip("/"),
            headers={"Authorization": f"Bearer {api_key}"},
            timeout=timeout,
        )

    def embed_documents(self, texts: Sequence[str]) -> np.ndarray:
        if not texts:
            return np.zeros((0, self.dim), dtype="float32")
        settings = get_settings()
        out: List[List[float]] = []
        # 分批，避免单次请求过大被限流
        for i in range(0, len(texts), settings.embed_batch_size):
            batch = list(texts[i : i + settings.embed_batch_size])
            resp = self._client.post("/embeddings", json={"model": self.model, "input": batch})
            resp.raise_for_status()
            data = resp.json()["data"]
            data.sort(key=lambda d: d.get("index", 0))
            out.extend(d["embedding"] for d in data)
        mat = np.asarray(out, dtype="float32")
        self.dim = mat.shape[1]  # 以服务端实际维度为准
        return self._l2_normalize(mat)


def get_embedder(provider: Optional[str] = None) -> BaseEmbedder:
    """工厂方法：按配置返回向量化实现。"""
    settings = get_settings()
    provider = (provider or settings.embed_provider).lower()

    if provider in ("mock", "local", "hash"):
        return HashEmbedder(dim=settings.embed_dim, model=settings.embed_model)

    if provider == "deepseek":
        # DeepSeek 目前没有公开 embedding 接口，工程上回落到本地向量
        logger.warning("DeepSeek 无 embedding 接口，自动回落到本地哈希向量")
        return HashEmbedder(dim=settings.embed_dim, model="mock-embed-384")

    if provider == "dashscope":
        return OpenAICompatEmbedder(
            base_url=settings.dashscope_base_url,
            api_key=settings.dashscope_api_key,
            model="text-embedding-v3",
            dim=1024,
            timeout=settings.llm_timeout,
        )

    if provider == "openai":
        return OpenAICompatEmbedder(
            base_url=settings.openai_base_url,
            api_key=settings.openai_api_key,
            model="text-embedding-3-small",
            dim=1536,
            timeout=settings.llm_timeout,
        )

    raise ValueError(f"未知的向量化提供方: {provider}")
