"""
混合检索编排（RAG 引擎）
========================
一条完整的检索链路：

    用户问题
       │
       ├─(可选) 查询改写 Query Rewrite ──┐
       ├─(可选) HyDE 假设文档 ───────────┤  多路查询
       │                                  │
       ▼                                  ▼
   ┌─────────────┐                 ┌─────────────┐
   │  向量检索    │                 │  BM25 检索   │
   │ (FAISS 内积) │                 │  (关键词)    │
   └──────┬──────┘                 └──────┬──────┘
          │                               │
          └────────── RRF 融合 ───────────┘
                        │
                  (可选) 重排 Rerank
                        │
                      Top-K

为什么必须混合：向量检索懂语义但会漏掉专有名词（工单号、型号、人名），
BM25 精确但不懂同义改写。RRF（Reciprocal Rank Fusion）用「名次」融合两路结果，
不需要两路分数量纲一致，工程上比加权求和稳健得多。
"""
from __future__ import annotations

import json
import logging
import re
import time
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np

from app.config import get_settings
from app.core.bm25 import Bm25Index
from app.core.embedding import BaseEmbedder, get_embedder
from app.core.llm import BaseLLM, get_llm, render_template
from app.core.tokenizer import cut
from app.core.vector_store import FAISS_AVAILABLE, ChunkMeta, VectorStore
from app.schemas import (
    IndexBuildRequest,
    IndexBuildResponse,
    RetrieveRequest,
    RetrieveResponse,
    RetrievedChunk,
)

logger = logging.getLogger(__name__)

STRATEGIES = ("VECTOR", "BM25", "HYBRID_RRF", "HYBRID_RERANK")


# ============================================================================
# 重排器
# ============================================================================
class HeuristicReranker:
    """
    无 LLM 时的重排：用 IDF 加权的词重叠近似「交叉编码器」。
    关键点：不是简单数共同词，而是**稀有词命中权重更高**（用 BM25 的 IDF）。
    """

    def __init__(self, bm25: Bm25Index) -> None:
        self.bm25 = bm25

    def score(self, question: str, content: str) -> float:
        q = set(cut(question))
        c = set(cut(content))
        if not q or not c:
            return 0.0
        hit = q & c
        max_possible = sum(self.bm25._idf(w) for w in q) or 1.0
        got = sum(self.bm25._idf(w) for w in hit)
        base = got / max_possible
        # 整句短语命中，说明高度相关
        phrase_bonus = 0.15 if question.strip() and question.strip() in content else 0.0
        # 过短的块信息量不足，轻微惩罚
        len_penalty = 0.05 if len(content) < 80 else 0.0
        return max(0.0, min(1.0, base * 0.85 + phrase_bonus - len_penalty))


class LlmReranker:
    """用大模型给候选打相关性分（Listwise Rerank），解析失败自动回落到启发式。"""

    def __init__(self, llm: BaseLLM, fallback: HeuristicReranker) -> None:
        self.llm = llm
        self.fallback = fallback

    def rerank(self, question: str, candidates: List[Tuple[int, str]]) -> Dict[int, float]:
        if not candidates:
            return {}
        lines = [f"[{i}] {text[:300]}" for i, (_cid, text) in enumerate(candidates, start=1)]
        prompt = render_template("RERANK", question=question, candidates="\n".join(lines))
        try:
            result = self.llm.complete([{"role": "user", "content": prompt}], task="rerank")
            arr = self._parse_json(result.content)
            if arr:
                out: Dict[int, float] = {}
                for item in arr:
                    idx = int(item.get("id", 0)) - 1
                    if 0 <= idx < len(candidates):
                        out[candidates[idx][0]] = max(0.0, min(1.0, float(item.get("score", 0)) / 10.0))
                if out:
                    return out
        except Exception as exc:
            logger.warning("LLM 重排失败，回落启发式: %s", exc)
        return {cid: self.fallback.score(question, text) for cid, text in candidates}

    @staticmethod
    def _parse_json(text: str) -> List[dict]:
        match = re.search(r"\[.*\]", text, re.S)
        if not match:
            return []
        try:
            data = json.loads(match.group(0))
            return data if isinstance(data, list) else []
        except Exception:
            return []


# ============================================================================
# 检索服务
# ============================================================================
class RetrievalService:
    """单个知识库的检索编排。"""

    def __init__(self, kb_id: int) -> None:
        self.kb_id = kb_id
        self.settings = get_settings()
        self.embedder: BaseEmbedder = get_embedder()
        self.store = VectorStore.get(kb_id, self.embedder.dim)
        self.bm25 = Bm25Index.get(kb_id)

    # ---------------- 建索引 ----------------
    def index_chunks(self, chunks: Sequence, rebuild: bool = True) -> IndexBuildResponse:
        t0 = time.time()
        if rebuild:
            self.store.reset()
            self.bm25.reset()

        texts = [c.content for c in chunks]
        vectors = self.embedder.embed_documents(texts) if texts else np.zeros((0, self.embedder.dim), "float32")
        ids = [int(c.chunk_id) for c in chunks]
        metas = [
            ChunkMeta(
                chunk_id=int(c.chunk_id),
                doc_id=int(c.doc_id),
                doc_name=getattr(c, "doc_name", "") or "",
                chunk_index=int(getattr(c, "chunk_index", 0)),
                content=c.content,
                page_no=getattr(c, "page_no", None),
            )
            for c in chunks
        ]
        if chunks:
            self.store.add(ids, vectors, metas)
            for c in chunks:
                self.bm25.add(int(c.chunk_id), c.content)
        self.store.save()
        self.bm25.save()

        return IndexBuildResponse(
            kb_id=self.kb_id,
            indexed_chunks=len(chunks),
            total_chunks=self.store.size,
            embed_dim=self.embedder.dim,
            embed_provider=self.embedder.provider,
            elapsed_ms=int((time.time() - t0) * 1000),
        )

    def delete_doc(self, doc_id: int) -> int:
        n = self.store.remove_doc(doc_id)
        for meta in list(self.store.all_chunks()):
            pass
        # BM25 侧同步删除
        for cid in [c for c in list(self.bm25.doc_ids)]:
            meta = self.store.metas.get(cid)
            if meta is None:
                self.bm25.remove(cid)
        self.store.save()
        self.bm25.save()
        return n

    # ---------------- 单路召回 ----------------
    def _vector_search(self, query: str, k: int) -> List[Tuple[int, float]]:
        if not FAISS_AVAILABLE:
            return []
        qv = self.embedder.embed_query(query)
        hits = self.store.search(qv, k)
        return [(h.chunk_id, h.score) for h in hits]

    def _bm25_search(self, query: str, k: int) -> List[Tuple[int, float]]:
        return [(h.chunk_id, h.score) for h in self.bm25.search(query, k)]

    # ---------------- 多路查询扩展 ----------------
    def _expand_queries(self, query: str, enable_rewrite: bool, enable_hyde: bool,
                        llm: Optional[BaseLLM]) -> Tuple[List[str], Optional[str], Optional[np.ndarray]]:
        """返回 (扩展查询列表, HyDE 文档, HyDE 向量)。"""
        queries = [query]
        hyde_doc: Optional[str] = None
        hyde_vec: Optional[np.ndarray] = None
        if llm is None:
            return queries, hyde_doc, hyde_vec

        if enable_rewrite:
            try:
                prompt = render_template("REWRITE", question=query)
                res = llm.complete([{"role": "user", "content": prompt}], task="rewrite")
                extra = [l.strip(" -•\t") for l in res.content.splitlines() if len(l.strip()) > 4]
                queries.extend(extra[:3])
            except Exception as exc:
                logger.warning("查询改写失败: %s", exc)

        if enable_hyde:
            try:
                prompt = render_template("HYDE", question=query)
                res = llm.complete([{"role": "user", "content": prompt}], task="hyde")
                hyde_doc = res.content.strip()
                if hyde_doc:
                    hyde_vec = self.embedder.embed_query(hyde_doc)
            except Exception as exc:
                logger.warning("HyDE 生成失败: %s", exc)

        # 去重且保持顺序
        seen, uniq = set(), []
        for q in queries:
            if q and q not in seen:
                seen.add(q)
                uniq.append(q)
        return uniq, hyde_doc, hyde_vec

    # ---------------- RRF 融合 ----------------
    @staticmethod
    def _rrf(ranked_lists: List[List[int]], k: int = 60) -> Dict[int, float]:
        """
        Reciprocal Rank Fusion：
            score(d) = Σ_lists 1 / (k + rank(d))
        只看名次，不看分数量纲，所以向量分和 BM25 分可以直接融合。
        """
        fused: Dict[int, float] = {}
        for lst in ranked_lists:
            for rank, cid in enumerate(lst, start=1):
                fused[cid] = fused.get(cid, 0.0) + 1.0 / (k + rank)
        return fused

    # ---------------- 主入口 ----------------
    def retrieve(self, req: RetrieveRequest) -> RetrieveResponse:
        t0 = time.time()
        s = self.settings
        strategy = (req.strategy or "HYBRID_RRF").upper()
        if strategy not in STRATEGIES:
            strategy = "HYBRID_RRF"
        top_k = req.top_k or s.top_k
        cand_k = req.candidate_k or s.candidate_k
        do_rewrite = s.enable_rewrite if req.enable_rewrite is None else req.enable_rewrite
        do_hyde = s.enable_hyde if req.enable_hyde is None else req.enable_hyde

        # 只有真实模型才值得做改写/HyDE（Mock 也能做，但默认只用于演示）
        llm = get_llm() if (do_rewrite or do_hyde) else None
        if llm is not None and llm.provider == "mock":
            # Mock 的改写质量有限，默认不参与，避免污染演示结果
            pass

        queries, hyde_doc, hyde_vec = self._expand_queries(req.query, do_rewrite, do_hyde, llm)

        # ---------- 各路召回 ----------
        vec_lists: List[List[int]] = []
        bm25_lists: List[List[int]] = []
        vec_scores: Dict[int, float] = {}
        bm_scores: Dict[int, float] = {}
        if strategy in ("VECTOR", "HYBRID_RRF", "HYBRID_RERANK"):
            for q in queries:
                pairs = self._vector_search(q, cand_k)
                vec_lists.append([cid for cid, _ in pairs])
                for cid, sc in pairs:
                    vec_scores[cid] = max(vec_scores.get(cid, -1.0), sc)
            if hyde_vec is not None and FAISS_AVAILABLE:
                hits = self.store.search(hyde_vec, cand_k)
                vec_lists.append([h.chunk_id for h in hits])
                for h in hits:
                    vec_scores[h.chunk_id] = max(vec_scores.get(h.chunk_id, -1.0), h.score)

        if strategy in ("BM25", "HYBRID_RRF", "HYBRID_RERANK"):
            for q in queries:
                pairs = self._bm25_search(q, cand_k)
                bm25_lists.append([cid for cid, _ in pairs])
                for cid, sc in pairs:
                    bm_scores[cid] = max(bm_scores.get(cid, 0.0), sc)

        # ---------- 融合 ----------
        use_rrf = len(vec_lists) + len(bm25_lists) > 1 or strategy.startswith("HYBRID")
        if strategy == "VECTOR":
            candidates = sorted(vec_scores.items(), key=lambda x: x[1], reverse=True)
        elif strategy == "BM25":
            candidates = sorted(bm_scores.items(), key=lambda x: x[1], reverse=True)
        else:
            fused = self._rrf(vec_lists + bm25_lists, k=s.rrf_k)
            candidates = sorted(fused.items(), key=lambda x: x[1], reverse=True)

        total_candidates = len(candidates)
        candidates = candidates[: max(top_k, cand_k)]

        # ---------- 重排 ----------
        rerank_scores: Dict[int, float] = {}
        if strategy == "HYBRID_RERANK" and candidates:
            heuristic = HeuristicReranker(self.bm25)
            payload = [
                (cid, self.store.metas[cid].content if cid in self.store.metas else "")
                for cid, _ in candidates[:cand_k]
            ]
            if llm is not None and llm.provider != "mock":
                rerank_scores = LlmReranker(llm, heuristic).rerank(req.query, payload)
            else:
                rerank_scores = {cid: heuristic.score(req.query, text) for cid, text in payload}
            candidates = sorted(
                candidates,
                key=lambda x: rerank_scores.get(x[0], 0.0) + x[1] * 0.1,
                reverse=True,
            )

        # ---------- 结果组装 ----------
        max_score = max((sc for _, sc in candidates), default=1.0) or 1.0
        results: List[RetrievedChunk] = []
        for rank, (cid, sc) in enumerate(candidates[:top_k], start=1):
            meta = self.store.metas.get(cid)
            if meta is None:
                continue
            if strategy == "VECTOR":
                final = float(sc)
            elif strategy == "BM25":
                final = float(sc) / (bm_scores and max(bm_scores.values()) or 1.0)
            elif strategy == "HYBRID_RERANK":
                final = float(rerank_scores.get(cid, 0.0))
            else:
                final = float(sc) / max_score
            # 重排分是 0-1 的绝对值，阈值要比向量/RRF 更松；但 0 分的块必须丢掉，
            # 否则会把「完全无关」的片段当引用返回，引用溯源就失去意义了
            floor = 0.05 if strategy == "HYBRID_RERANK" else s.score_threshold
            if final < floor:
                continue
            results.append(
                RetrievedChunk(
                    chunk_id=cid,
                    doc_id=meta.doc_id,
                    doc_name=meta.doc_name,
                    chunk_index=meta.chunk_index,
                    content=meta.content,
                    page_no=meta.page_no,
                    score=round(final, 6),
                    vector_score=round(vec_scores.get(cid, 0.0), 6) if cid in vec_scores else None,
                    bm25_score=round(bm_scores.get(cid, 0.0), 6) if cid in bm_scores else None,
                    rrf_score=round(sc, 6) if strategy.startswith("HYBRID") else None,
                    rerank_score=round(rerank_scores.get(cid, 0.0), 6) if cid in rerank_scores else None,
                    rank=rank,
                )
            )
        # 重新编号名次，保证连续
        for i, r in enumerate(results, start=1):
            r.rank = i

        return RetrieveResponse(
            kb_id=self.kb_id,
            query=req.query,
            rewritten_queries=queries[1:],
            hyde_document=hyde_doc,
            strategy=strategy,
            total_candidates=total_candidates,
            results=results,
            elapsed_ms=int((time.time() - t0) * 1000),
        )

    def stats(self) -> Dict:
        return {
            "kb_id": self.kb_id,
            "vectors": self.store.size,
            "bm25_docs": self.bm25.size,
            "embed_provider": self.embedder.provider,
            "embed_dim": self.embedder.dim,
            "faiss_available": FAISS_AVAILABLE,
        }


# 每个知识库一个服务实例（避免重复加载索引）
_SERVICES: Dict[int, RetrievalService] = {}


def get_service(kb_id: int) -> RetrievalService:
    svc = _SERVICES.get(kb_id)
    if svc is None:
        svc = RetrievalService(kb_id)
        _SERVICES[kb_id] = svc
    return svc
