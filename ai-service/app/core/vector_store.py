"""
FAISS 向量库封装
================
用 IndexIDMap2 包一层 IndexFlatIP：

    IndexFlatIP   —— 内积检索。因为向量已 L2 归一化，内积 == 余弦相似度
    IndexIDMap2   —— 让我能自己指定向量 ID（直接用业务库的 chunk_id），
                     否则 FAISS 只认 0,1,2... 连续下标，删除/更新会很难受

每个知识库一个独立索引目录：
    data/indexes/kb_{kb_id}/
        ├── index.faiss     二进制索引
        └── meta.json       每个向量对应的 chunk 元数据（文档、页码、正文）
"""
from __future__ import annotations

import json
import logging
import os
import threading
from dataclasses import asdict, dataclass, field
from typing import Dict, List, Optional, Tuple

import numpy as np

from app.config import get_settings

logger = logging.getLogger(__name__)

try:  # FAISS 在 Windows 上偶尔缺失，做成软依赖，缺失时服务仍能启动（只是不能做向量检索）
    import faiss

    FAISS_AVAILABLE = True
except Exception as _exc:  # pragma: no cover
    faiss = None  # type: ignore
    FAISS_AVAILABLE = False
    logger.warning("FAISS 不可用，向量检索将被禁用: %s", _exc)


@dataclass
class ChunkMeta:
    """索引里每个向量对应的业务元数据。"""

    chunk_id: int
    doc_id: int
    doc_name: str = ""
    chunk_index: int = 0
    content: str = ""
    page_no: Optional[int] = None

    def to_dict(self) -> Dict:
        return asdict(self)


@dataclass
class SearchHit:
    chunk_id: int
    score: float
    meta: Optional[ChunkMeta] = None


class VectorStore:
    """单个知识库的向量索引（进程内单例，按 kb_id 缓存）。"""

    _lock = threading.RLock()
    _instances: Dict[int, "VectorStore"] = {}

    def __init__(self, kb_id: int, dim: int) -> None:
        self.kb_id = kb_id
        self.dim = dim
        self.metas: Dict[int, ChunkMeta] = {}
        self.index = None
        self._dirty = False
        if FAISS_AVAILABLE:
            # 内积 + ID 映射
            self.index = faiss.IndexIDMap2(faiss.IndexFlatIP(dim))

    # ---------------- 单例管理 ----------------
    @classmethod
    def get(cls, kb_id: int, dim: int) -> "VectorStore":
        with cls._lock:
            store = cls._instances.get(kb_id)
            if store is None or store.dim != dim:
                store = cls(kb_id, dim)
                store.load()
                cls._instances[kb_id] = store
            return store

    @classmethod
    def drop(cls, kb_id: int) -> None:
        with cls._lock:
            cls._instances.pop(kb_id, None)

    # ---------------- 路径 ----------------
    @property
    def _dir(self) -> str:
        d = os.path.join(str(get_settings().index_path), f"kb_{self.kb_id}")
        os.makedirs(d, exist_ok=True)
        return d

    @property
    def _index_file(self) -> str:
        return os.path.join(self._dir, "index.faiss")

    @property
    def _meta_file(self) -> str:
        return os.path.join(self._dir, "meta.json")

    # ---------------- 写入 ----------------
    def reset(self) -> None:
        """清空索引（重建前调用）。"""
        if FAISS_AVAILABLE:
            self.index = faiss.IndexIDMap2(faiss.IndexFlatIP(self.dim))
        self.metas.clear()
        self._dirty = True

    def add(self, ids: List[int], vectors: np.ndarray, metas: List[ChunkMeta]) -> None:
        """加入向量。ids 用业务库的 chunk_id，直接可回查 MySQL。"""
        if len(ids) != len(vectors) or len(ids) != len(metas):
            raise ValueError("ids / vectors / metas 长度必须一致")
        if vectors.shape[1] != self.dim:
            raise ValueError(f"向量维度不匹配：索引 {self.dim}，输入 {vectors.shape[1]}")
        # FAISS 缺失时仍然保存元数据，这样纯 BM25 策略依旧可用
        if FAISS_AVAILABLE and self.index is not None:
            arr = np.ascontiguousarray(vectors.astype("float32"))
            id_arr = np.asarray(ids, dtype="int64")
            # 先删同 ID 旧向量，实现「覆盖式」增量更新
            try:
                self.index.remove_ids(id_arr)
            except Exception:
                pass
            self.index.add_with_ids(arr, id_arr)
        for m in metas:
            self.metas[m.chunk_id] = m
        self._dirty = True

    def remove_doc(self, doc_id: int) -> int:
        """删除某文档的全部向量（删文档时调用）。"""
        target = [cid for cid, m in self.metas.items() if m.doc_id == doc_id]
        if target and FAISS_AVAILABLE:
            self.index.remove_ids(np.asarray(target, dtype="int64"))
        for cid in target:
            self.metas.pop(cid, None)
        self._dirty = True
        return len(target)

    def save(self) -> None:
        if not self._dirty:
            return
        if FAISS_AVAILABLE and self.index is not None:
            faiss.write_index(self.index, self._index_file)
        with open(self._meta_file, "w", encoding="utf-8") as f:
            json.dump({str(k): v.to_dict() for k, v in self.metas.items()}, f, ensure_ascii=False)
        self._dirty = False

    # ---------------- 读取 ----------------
    def load(self) -> None:
        try:
            if FAISS_AVAILABLE and os.path.exists(self._index_file):
                self.index = faiss.read_index(self._index_file)
            if os.path.exists(self._meta_file):
                with open(self._meta_file, "r", encoding="utf-8") as f:
                    raw = json.load(f)
                self.metas = {int(k): ChunkMeta(**v) for k, v in raw.items()}
        except Exception as exc:  # 索引损坏时退化，不让服务起不来
            logger.error("加载 kb_%s 索引失败: %s", self.kb_id, exc)
            self.metas = {}

    @property
    def size(self) -> int:
        return int(self.index.ntotal) if (FAISS_AVAILABLE and self.index is not None) else 0

    def search(self, query_vector: np.ndarray, top_k: int = 20) -> List[SearchHit]:
        """向量检索，返回 [(chunk_id, 余弦相似度)]。"""
        if not FAISS_AVAILABLE or self.index is None or self.index.ntotal == 0:
            return []
        q = np.ascontiguousarray(query_vector.reshape(1, -1).astype("float32"))
        scores, ids = self.index.search(q, min(top_k, self.index.ntotal))
        hits: List[SearchHit] = []
        for score, cid in zip(scores[0], ids[0]):
            if cid == -1:
                continue
            hits.append(SearchHit(chunk_id=int(cid), score=float(score), meta=self.metas.get(int(cid))))
        return hits

    def all_chunks(self) -> List[ChunkMeta]:
        return list(self.metas.values())
