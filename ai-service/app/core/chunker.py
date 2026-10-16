"""
文本分块（Chunking）
====================
RAG 的效果有一半取决于分块。这里用的是「结构优先 + 长度兜底」的策略：

    1. 先按空行 / 标题 / 列表项切成语义段落
    2. 再按 chunk_size 把段落贪心装进块里，装不下就开新块
    3. 相邻块之间保留 chunk_overlap 个字符的重叠，避免答案被切断
    4. 每个块都记住自己的页码与原文偏移，供引用溯源使用

为什么不用「定长硬切」：硬切会把一句话劈成两半，向量语义就废了。
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import List

from app.core.parser import ParsedDocument

# 段落分隔：空行、Markdown 标题、有序/无序列表项
_PARAGRAPH_SPLIT = re.compile(r"\n\s*\n|(?=\n#{1,6}\s)|(?=\n\s*(?:[-*+]|\d+[.、)])\s)")


@dataclass
class Chunk:
    chunk_index: int
    content: str
    page_no: int | None
    char_start: int
    char_end: int

    @property
    def token_count(self) -> int:
        """用字符数近似 token 数：中文约 1 字 ≈ 1 token，工程上足够用。"""
        return len(self.content)


def _split_paragraphs(text: str) -> List[tuple[int, str]]:
    """返回 [(段落起始偏移, 段落文本), ...]。"""
    result: List[tuple[int, str]] = []
    pos = 0
    for piece in _PARAGRAPH_SPLIT.split(text):
        if piece is None:
            continue
        # split 用零宽断言时会产生空串，偏移要精确累加
        idx = text.find(piece, pos)
        if idx < 0:
            idx = pos
        if piece.strip():
            result.append((idx, piece.strip()))
        pos = idx + len(piece)
    return result


def chunk_document(
    doc: ParsedDocument,
    chunk_size: int = 500,
    chunk_overlap: int = 80,
    min_chunk_size: int = 60,
    respect_page: bool = True,
) -> List[Chunk]:
    """
    把解析结果切成检索单元。

    respect_page（默认 True）：**分块不跨页**。
    为什么重要：如果允许一个块横跨第 2、3 页，块只能记一个 page_no，
    而命中内容可能恰恰在第 3 页——引用溯源就会指错页。
    代价是页尾可能留下一个偏小的块（比指错页划算得多）。
    """
    if chunk_size <= 0:
        raise ValueError("chunk_size 必须为正数")
    chunk_overlap = max(0, min(chunk_overlap, chunk_size // 2))

    paragraphs = _split_paragraphs(doc.full_text)
    if not paragraphs:
        return []

    # 预先给每段标上页码，供「跨页强制切块」判断
    items = [(start, text, doc.page_at(start)) for start, text in paragraphs]

    chunks: List[Chunk] = []
    buf: List[str] = []
    buf_start = items[0][0]
    buf_len = 0
    cur_page = items[0][2]

    def flush(end_offset: int, keep_overlap: bool = True, force: bool = False) -> None:
        nonlocal buf, buf_start, buf_len
        content = "\n".join(buf).strip()
        # 当前块在全文中的真实结束位置（比 next_para_start 更准，因为段落可能被丢弃）
        content_end = buf_start + len(content)
        if force or len(content) >= min_chunk_size or (content and not chunks):
            chunks.append(
                Chunk(
                    chunk_index=len(chunks),
                    content=content,
                    page_no=doc.page_at(buf_start),
                    char_start=buf_start,
                    char_end=content_end,
                )
            )
        # 重叠：把上一块的尾部若干字符作为下一块的开头。
        # ★ 跨页切块时必须关掉重叠，否则上一页的尾巴会带进下一页，页码又错了。
        if keep_overlap and chunk_overlap > 0 and content:
            tail = content[-chunk_overlap:]
            buf = [tail]
            buf_len = len(tail)
            # ★ 关键：带重叠时也要把起点往前推，否则后续所有块的页码/偏移
            #   都会停在第一块（页码溯源就全错了）
            buf_start = max(0, content_end - len(tail))
        else:
            buf = []
            buf_len = 0

    for start, para, page in items:
        # 换页 → 强制切块，保证每个块只属于一页（页码才能精确）
        if respect_page and buf and page is not None and cur_page is not None and page != cur_page:
            flush(start, keep_overlap=False, force=True)
            cur_page = page
        if not buf:
            buf_start = start
        if page is not None:
            cur_page = page

        para_len = len(para) + (1 if buf else 0)
        if buf_len + para_len > chunk_size and buf:
            flush(start)
            if not buf:
                buf_start = start
        buf.append(para)
        buf_len += para_len

    if buf:
        flush(len(doc.full_text), keep_overlap=False, force=True)

    # 重新编号，保证 chunk_index 连续
    for i, c in enumerate(chunks):
        c.chunk_index = i
    return chunks
