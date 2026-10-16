"""
多格式文档解析
==============
支持 PDF / Word(docx) / Markdown / TXT，统一输出「带页码的文本块」：

    ParsedDocument
      ├── blocks: [TextBlock(page_no, text), ...]   # 保留页码，供引用溯源
      ├── full_text: str                            # 拼接后的全文
      └── offset_map: [(start, end, page_no), ...]  # 偏移 -> 页码 的映射表

为什么一定要保留页码：RAG 的引用溯源要能回答「这句话出自第几页」，
如果一开始就把 PDF 拍平成一段字符串，页码信息就永久丢失了。
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import List, Tuple

from app.core.tokenizer import normalize_whitespace


@dataclass
class TextBlock:
    """一段来自某一页的文本。"""

    page_no: int
    text: str


@dataclass
class ParsedDocument:
    file_name: str
    doc_type: str
    blocks: List[TextBlock] = field(default_factory=list)
    full_text: str = ""
    # (起始偏移, 结束偏移, 页码)
    offset_map: List[Tuple[int, int, int]] = field(default_factory=list)

    @property
    def page_count(self) -> int:
        return max((b.page_no for b in self.blocks), default=0)

    @property
    def char_count(self) -> int:
        return len(self.full_text)

    def page_at(self, offset: int) -> int | None:
        """给定字符偏移，反查它属于第几页（供分块使用）。"""
        for start, end, page in self.offset_map:
            if start <= offset < end:
                return page
        return self.offset_map[-1][2] if self.offset_map else None


def detect_doc_type(path: str) -> str:
    ext = os.path.splitext(path)[1].lower().lstrip(".")
    if ext in ("md", "markdown"):
        return "md"
    if ext in ("docx", "doc"):
        return "docx"
    if ext == "pdf":
        return "pdf"
    if ext in ("txt", "text", ""):
        return "txt"
    return ext


def _build(doc_type: str, file_name: str, blocks: List[TextBlock]) -> ParsedDocument:
    """把文本块拼成全文，并建立「偏移 -> 页码」映射。"""
    doc = ParsedDocument(file_name=file_name, doc_type=doc_type, blocks=blocks)
    parts: List[str] = []
    offset = 0
    for b in blocks:
        text = normalize_whitespace(b.text)
        if not text:
            continue
        if parts:
            parts.append("\n\n")
            offset += 2
        start = offset
        parts.append(text)
        offset += len(text)
        doc.offset_map.append((start, offset, b.page_no))
    doc.full_text = "".join(parts)
    return doc


def parse_pdf(path: str, file_name: str) -> ParsedDocument:
    """用 pypdf 逐页抽取文本，页码从 1 开始。"""
    from pypdf import PdfReader

    reader = PdfReader(path)
    blocks: List[TextBlock] = []
    for i, page in enumerate(reader.pages, start=1):
        try:
            text = page.extract_text() or ""
        except Exception as exc:  # 单页失败不影响整篇
            text = f"[第 {i} 页解析失败: {exc}]"
        blocks.append(TextBlock(page_no=i, text=text))
    return _build("pdf", file_name, blocks)


def parse_docx(path: str, file_name: str) -> ParsedDocument:
    """
    用 python-docx 抽取段落与表格。
    Word 没有物理页码，这里统一记为第 1 页，但保留段落顺序。
    """
    import docx  # python-docx

    document = docx.Document(path)
    pieces: List[str] = []
    for para in document.paragraphs:
        if para.text.strip():
            pieces.append(para.text)
    # 表格也抽出来，很多产品手册的关键参数在表格里
    for table in document.tables:
        for row in table.rows:
            cells = [c.text.strip() for c in row.cells if c.text.strip()]
            if cells:
                pieces.append(" | ".join(cells))
    return _build("docx", file_name, [TextBlock(page_no=1, text="\n".join(pieces))])


def parse_text(path: str, file_name: str) -> ParsedDocument:
    """Markdown / TXT：按一级标题切块，并把标题行号当作「页」的近似。"""
    for enc in ("utf-8", "utf-8-sig", "gbk", "latin-1"):
        try:
            with open(path, "r", encoding=enc) as f:
                raw = f.read()
            break
        except UnicodeDecodeError:
            continue
    else:  # pragma: no cover
        raw = ""

    doc_type = detect_doc_type(path)
    blocks: List[TextBlock] = []
    page = 1
    buf: List[str] = []
    for line in raw.splitlines():
        # Markdown 里用 ## 分节，把每节当作一个逻辑页，便于溯源
        if doc_type == "md" and line.startswith("## ") and buf:
            blocks.append(TextBlock(page_no=page, text="\n".join(buf)))
            buf = []
            page += 1
        buf.append(line)
    if buf:
        blocks.append(TextBlock(page_no=page, text="\n".join(buf)))
    if not blocks:
        blocks = [TextBlock(page_no=1, text=raw)]
    return _build(doc_type, file_name, blocks)


def parse_document(path: str) -> ParsedDocument:
    """统一入口：按扩展名分发解析器。"""
    if not os.path.exists(path):
        raise FileNotFoundError(f"文件不存在: {path}")
    file_name = os.path.basename(path)
    doc_type = detect_doc_type(path)
    if doc_type == "pdf":
        return parse_pdf(path, file_name)
    if doc_type == "docx":
        return parse_docx(path, file_name)
    return parse_text(path, file_name)
