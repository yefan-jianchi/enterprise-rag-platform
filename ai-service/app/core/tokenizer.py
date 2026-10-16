"""
中文分词与文本归一化
====================
BM25 和本地 Mock 向量都依赖分词，所以单独抽一层：
    * jieba 精确模式切词（中文）
    * 保留英文单词与数字（正则兜底）
    * 过滤停用词与单字符噪声
    * 额外产出「字符二元组」，缓解未登录词（OOV）导致的召回丢失
"""
from __future__ import annotations

import re
from functools import lru_cache
from typing import Iterable, List, Set

import jieba

# 常见中文停用词（够用即可，工程上可换成完整停用词表文件）
STOPWORDS: Set[str] = {
    "的", "了", "和", "是", "就", "都", "而", "及", "与", "着", "或", "一个", "没有",
    "我们", "你们", "他们", "自己", "这", "那", "这个", "那个", "什么", "怎么", "如何",
    "可以", "需要", "进行", "通过", "以及", "但是", "因为", "所以", "如果", "并且",
    "在", "有", "被", "把", "让", "给", "对", "从", "到", "为", "以", "于", "中",
    "a", "an", "the", "is", "are", "of", "to", "and", "or", "in", "on", "for",
}

# 只保留：中文、英文、数字
_TOKEN_RE = re.compile(r"[\u4e00-\u9fa5]+|[a-zA-Z]+|\d+")


@lru_cache(maxsize=1)
def _ensure_jieba_ready() -> bool:
    """初始化 jieba 词典（首次调用会加载约 1 秒，之后走缓存）。"""
    jieba.initialize()
    return True


def cut(text: str) -> List[str]:
    """切词并过滤停用词，返回小写 token 列表。"""
    _ensure_jieba_ready()
    tokens: List[str] = []
    for word in jieba.cut(text, cut_all=False):
        word = word.strip().lower()
        if not word or word in STOPWORDS:
            continue
        if not _TOKEN_RE.fullmatch(word):
            # jieba 会切出标点、空白，这里直接丢弃
            continue
        if len(word) == 1 and not word.isdigit() and not re.match(r"[a-z]", word):
            # 单个汉字信息量太低（如「卡」「了」），丢掉；数字和字母保留
            continue
        tokens.append(word)
    return tokens


def char_bigrams(text: str) -> List[str]:
    """
    字符二元组：中文场景下的「兜底召回」。
    例：「校园卡」→ ['校园', '园卡']
    好处：即使 jieba 没收录某个新词，字面重叠也能被向量/BM25 捕捉到。
    """
    chars = [c for c in text if "\u4e00" <= c <= "\u9fa5"]
    return [chars[i] + chars[i + 1] for i in range(len(chars) - 1)]


def tokenize_for_index(text: str) -> List[str]:
    """建索引/建向量用的 token 序列（词 + 字二元组）。"""
    return cut(text) + char_bigrams(text)


def tokenize_for_query(text: str) -> List[str]:
    """查询侧分词，与索引侧保持同一套规则。"""
    return tokenize_for_index(text)


def join_tokens(tokens: Iterable[str]) -> str:
    return " ".join(tokens)


def normalize_whitespace(text: str) -> str:
    """统一换行与空白，避免分块时被奇怪的空格干扰。"""
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"[ \t\u3000]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()
