"""
BM25 关键词检索（自实现）
=========================
为什么不用现成库：BM25 是 RAG 混合检索的另一半，自己实现才能讲清楚
「为什么关键词检索能补上向量检索的短板」，也方便按业务调参。

用的是 Okapi BM25：

                       (N - df + 0.5)
    IDF(qi) = ln( --------------------- + 1 )
                       (df + 0.5)

                        f(qi, D) * (k1 + 1)
    score(D, Q) = Σ IDF(qi) * ------------------------------------
                              f(qi,D) + k1 * (1 - b + b * |D|/avgdl)

    k1 = 1.5  控制词频饱和（一个词出现 10 次不等于重要 10 倍）
    b  = 0.75 控制文档长度归一化（长文档不该因为长就占便宜）

中文场景的关键：分词要带「字符二元组」兜底，否则专业术语一多，召回就崩。
"""
from __future__ import annotations

import json
import math
import os
from collections import Counter
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

from app.config import get_settings
from app.core.tokenizer import tokenize_for_index


@dataclass
class Bm25Hit:
    chunk_id: int
    score: float


class Bm25Index:
    """一个知识库一份 BM25 索引（内存中，持久化到 JSON）。"""

    _instances: Dict[int, "Bm25Index"] = {}

    def __init__(self, kb_id: int, k1: float = 1.5, b: float = 0.75) -> None:
        self.kb_id = kb_id
        self.k1 = k1
        self.b = b
        self.doc_ids: List[int] = []                      # 位置下标 -> chunk_id
        self.doc_tf: List[Counter] = []                   # 每个文档的词频
        self.doc_len: List[int] = []                      # 每个文档的 token 数
        self.df: Counter = Counter()                      # 词 -> 出现的文档数
        self.avgdl: float = 0.0
        self._dirty = False

    # ---------------- 单例 ----------------
    @classmethod
    def get(cls, kb_id: int) -> "Bm25Index":
        idx = cls._instances.get(kb_id)
        if idx is None:
            idx = cls(kb_id)
            idx.load()
            cls._instances[kb_id] = idx
        return idx

    @classmethod
    def drop(cls, kb_id: int) -> None:
        cls._instances.pop(kb_id, None)

    # ---------------- 路径 ----------------
    @property
    def _file(self) -> str:
        d = os.path.join(str(get_settings().index_path), f"kb_{self.kb_id}")
        os.makedirs(d, exist_ok=True)
        return os.path.join(d, "bm25.json")

    # ---------------- 构建 ----------------
    def reset(self) -> None:
        self.doc_ids, self.doc_tf, self.doc_len, self.df = [], [], [], Counter()
        self.avgdl = 0.0
        self._dirty = True

    def add(self, chunk_id: int, content: str) -> None:
        tokens = tokenize_for_index(content)
        tf = Counter(tokens)
        # 覆盖式更新：同 chunk_id 先删旧记录
        if chunk_id in self.doc_ids:
            self.remove(chunk_id)
        self.doc_ids.append(chunk_id)
        self.doc_tf.append(tf)
        self.doc_len.append(len(tokens))
        for word in tf:
            self.df[word] += 1
        self.avgdl = sum(self.doc_len) / len(self.doc_len) if self.doc_len else 0.0
        self._dirty = True

    def remove(self, chunk_id: int) -> None:
        if chunk_id not in self.doc_ids:
            return
        i = self.doc_ids.index(chunk_id)
        for word in self.doc_tf[i]:
            self.df[word] -= 1
            if self.df[word] <= 0:
                del self.df[word]
        self.doc_ids.pop(i)
        self.doc_tf.pop(i)
        self.doc_len.pop(i)
        self.avgdl = sum(self.doc_len) / len(self.doc_len) if self.doc_len else 0.0
        self._dirty = True

    def save(self) -> None:
        if not self._dirty:
            return
        payload = {
            "k1": self.k1,
            "b": self.b,
            "doc_ids": self.doc_ids,
            "doc_tf": [dict(tf) for tf in self.doc_tf],
            "doc_len": self.doc_len,
        }
        with open(self._file, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False)
        self._dirty = False

    def load(self) -> None:
        if not os.path.exists(self._file):
            return
        try:
            with open(self._file, "r", encoding="utf-8") as f:
                p = json.load(f)
            self.k1 = p.get("k1", 1.5)
            self.b = p.get("b", 0.75)
            self.doc_ids = p.get("doc_ids", [])
            self.doc_tf = [Counter(d) for d in p.get("doc_tf", [])]
            self.doc_len = p.get("doc_len", [])
            df: Counter = Counter()
            for tf in self.doc_tf:
                for word in tf:
                    df[word] += 1
            self.df = df
            self.avgdl = sum(self.doc_len) / len(self.doc_len) if self.doc_len else 0.0
        except Exception:
            self.reset()

    # ---------------- 检索 ----------------
    def _idf(self, word: str) -> float:
        n = len(self.doc_ids)
        df = self.df.get(word, 0)
        if n == 0:
            return 0.0
        # 加 1 防止 df=N 时 IDF 为负（BM25+ 的常见做法）
        return math.log((n - df + 0.5) / (df + 0.5) + 1.0)

    def search(self, query: str, top_k: int = 20) -> List[Bm25Hit]:
        if not self.doc_ids:
            return []
        q_tokens = tokenize_for_index(query)
        if not q_tokens:
            return []
        scores: List[Tuple[int, float]] = []
        n = len(self.doc_ids)
        for i in range(n):
            tf = self.doc_tf[i]
            dl = self.doc_len[i] or 1
            score = 0.0
            for word in q_tokens:
                f = tf.get(word, 0)
                if not f:
                    continue
                idf = self._idf(word)
                if idf <= 0:
                    continue
                denom = f + self.k1 * (1 - self.b + self.b * dl / (self.avgdl or 1.0))
                score += idf * f * (self.k1 + 1) / denom
            if score > 0:
                scores.append((i, score))
        scores.sort(key=lambda x: x[1], reverse=True)
        return [Bm25Hit(chunk_id=self.doc_ids[i], score=s) for i, s in scores[:top_k]]

    @property
    def size(self) -> int:
        return len(self.doc_ids)
