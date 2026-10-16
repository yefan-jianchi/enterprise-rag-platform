"""
多格式解析器自测（PDF / Word / Markdown）
========================================
验证三件事：
    1. 三种格式都能抽出文本
    2. **PDF 的页码被正确保留**（引用溯源的前提）
    3. 解析结果能正常分块并进入检索链路

前置：先跑一次 tests/make_fixtures.py 生成 PDF/DOCX 样本。

运行：
    cd ai-service
    python tests/test_parsers.py
"""
from __future__ import annotations

import os
import sys

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if BASE_DIR not in sys.path:
    sys.path.insert(0, BASE_DIR)

from app.core.chunker import chunk_document                # noqa: E402
from app.core.parser import parse_document                 # noqa: E402
from app.core.retrieval import get_service                 # noqa: E402
from app.schemas import IndexChunkIn, RetrieveRequest      # noqa: E402

SAMPLES = os.path.join(BASE_DIR, "data", "samples")
KB_PARSER = 9003


def _path(name: str) -> str:
    p = os.path.join(SAMPLES, name)
    if not os.path.exists(p):
        raise SystemExit(f"缺少样本 {name}，请先执行： python tests/make_fixtures.py")
    return p


def test_markdown() -> None:
    print("[1/4] Markdown 解析")
    doc = parse_document(_path("校园服务指南.md"))
    print(f"  类型={doc.doc_type} 节数={doc.page_count} 字符={doc.char_count}")
    assert doc.char_count > 500
    assert doc.page_count >= 5, "Markdown 应按 ## 分节，保留逻辑页码"
    chunks = chunk_document(doc)
    assert chunks, "应能切出分块"
    print(f"  分块={len(chunks)}  首块页码={chunks[0].page_no}")


def test_docx() -> None:
    print("\n[2/4] Word(docx) 解析")
    doc = parse_document(_path("宿舍与后勤服务.docx"))
    print(f"  类型={doc.doc_type} 字符={doc.char_count}")
    assert doc.doc_type == "docx"
    assert "定额用电" in doc.full_text, "正文应被抽出"
    assert "菜鸟驿站" in doc.full_text, "★ 表格内容也应被抽出（很多实现会漏掉表格）"
    chunks = chunk_document(doc)
    assert chunks
    print(f"  分块={len(chunks)}  含表格关键词: {'菜鸟驿站' in doc.full_text}")


def test_pdf() -> None:
    print("\n[3/4] PDF 解析（重点验证页码）")
    doc = parse_document(_path("图书馆使用手册.pdf"))
    print(f"  类型={doc.doc_type} 页数={doc.page_count} 字符={doc.char_count}")
    assert doc.doc_type == "pdf"
    assert doc.page_count >= 4, "PDF 应逐页解析，保留真实页数"
    assert "借阅规则" in doc.full_text

    # 页码映射必须连续且覆盖全文
    covered = sum(end - start for start, end, _ in doc.offset_map)
    print(f"  页码映射段数={len(doc.offset_map)} 覆盖字符={covered}/{doc.char_count}")
    assert len(doc.offset_map) == doc.page_count

    # 关键：不同位置的文本要能反查到不同页码
    p_borrow = doc.page_at(doc.full_text.find("借阅规则"))
    p_vpn = doc.page_at(doc.full_text.find("WebVPN"))
    print(f"  「借阅规则」在第 {p_borrow} 页，「WebVPN」在第 {p_vpn} 页")
    assert p_borrow != p_vpn, "★ 不同内容必须落在不同页码上，否则溯源就是假的"

    chunks = chunk_document(doc)
    pages = sorted({c.page_no for c in chunks})
    print(f"  分块={len(chunks)}  覆盖页码={pages}")
    assert len(pages) >= 2, "★ 分块应跨越多个页码，否则跨页溯源无从验证"


def test_pdf_retrieval() -> None:
    print("\n[4/4] PDF 入库后检索（含页码溯源）")
    svc = get_service(KB_PARSER)
    svc.store.reset()
    svc.bm25.reset()

    doc = parse_document(_path("图书馆使用手册.pdf"))
    chunks = chunk_document(doc)
    payload = [
        IndexChunkIn(chunk_id=90030000 + i, doc_id=1, doc_name="图书馆使用手册.pdf",
                     chunk_index=c.chunk_index, content=c.content, page_no=c.page_no)
        for i, c in enumerate(chunks)
    ]
    res = svc.index_chunks(payload, rebuild=True)
    print(f"  索引 {res.indexed_chunks} 块（维度 {res.embed_dim}）")

    resp = svc.retrieve(RetrieveRequest(kb_id=KB_PARSER, query="校外怎么访问知网？",
                                        strategy="HYBRID_RERANK", top_k=3))
    top = resp.results[0] if resp.results else None
    assert top is not None, "应检索到内容"
    print(f"  Top1: {top.doc_name} 第{top.page_no}页 score={top.score:.3f}")
    print(f"         {top.content[:70]}...")
    assert top.page_no and top.page_no > 1, "★ 命中的应该是第 3 页左右的电子资源段落"


if __name__ == "__main__":
    print("=" * 68)
    print("  多格式解析器自测")
    print("=" * 68)
    test_markdown()
    test_docx()
    test_pdf()
    test_pdf_retrieval()
    print("\n全部通过")
