"""
LangChain 桥接层自测
====================
验证「同一套索引、两条实现路径」：

    /api/v1/rag/ask            → 手写链路（retrieval.py）
    /api/v1/langchain/rag/ask  → LangChain LCEL 链（langchain_bridge.py）

两条路径应该召回**完全相同的引用**（同 chunk_id / 页码 / 分数），
只差在编排方式。这既是功能验证，也是「我理解 LangChain 底层在做什么」的证据。

运行：
    cd ai-service
    python tests/test_langchain_bridge.py
"""
from __future__ import annotations

import os
import sys

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if BASE_DIR not in sys.path:
    sys.path.insert(0, BASE_DIR)

from app.core.retrieval import get_service                     # noqa: E402
from app.integrations.langchain_bridge import (                 # noqa: E402
    LANGCHAIN_AVAILABLE,
    answer_with_langchain,
    build_rag_chain,
    langchain_status,
)
from app.schemas import RetrieveRequest                         # noqa: E402

KB = 9001          # 校园服务知识库（由 tests/test_pipeline.py 建好索引）
QUESTION = "校园卡丢了怎么补办？"


def test_langchain_available() -> None:
    print("[1/4] LangChain 可用性")
    st = langchain_status()
    print(f"  available={st.get('available')}  version={st.get('version', '-')}")
    if not st.get("available"):
        print(f"  跳过：{st.get('error')}")
        return
    assert st["available"] is True


def test_retriever_returns_documents() -> None:
    print("\n[2/4] RagRetriever 返回 LangChain Document")
    if not LANGCHAIN_AVAILABLE:
        print("  跳过")
        return
    _, retriever = build_rag_chain(KB, top_k=3)
    docs = retriever.invoke(QUESTION)
    for d in docs:
        md = d.metadata
        print(f"  - {md['doc_name']} 第{md['page_no']}页 chunk#{md['chunk_id']} score={md['score']:.3f}")
    assert docs, "检索结果不应为空"
    assert "page_no" in docs[0].metadata, "必须带页码元数据，否则引用溯源做不了"


def test_two_paths_agree() -> None:
    """★ 核心断言：手写链路与 LangChain 链路召回同一批 chunk。"""
    print("\n[3/4] 两条链路召回一致性")
    svc = get_service(KB)
    hand = svc.retrieve(RetrieveRequest(kb_id=KB, query=QUESTION, strategy="HYBRID_RERANK", top_k=3))
    hand_ids = [r.chunk_id for r in hand.results]

    if not LANGCHAIN_AVAILABLE:
        print("  跳过（LangChain 不可用）")
        return
    lc = answer_with_langchain(QUESTION, kb_id=KB, top_k=3)
    lc_ids = [d.metadata["chunk_id"] for d in lc["documents"]]

    print(f"  手写链路   : {hand_ids}")
    print(f"  LangChain  : {lc_ids}")
    assert hand_ids == lc_ids, "两条链路必须召回同一批 chunk（它们共用同一套索引）"
    print("  ✔ 一致")


def test_langchain_answer() -> None:
    print("\n[4/4] LangChain 链生成回答")
    if not LANGCHAIN_AVAILABLE:
        print("  跳过")
        return
    r = answer_with_langchain(QUESTION, kb_id=KB, top_k=3)
    print("  engine =", r["engine"])
    print("  " + r["answer"].replace("\n", "\n  ")[:400])
    assert r["answer"], "回答不应为空"
    assert r["documents"], "应带回引用文档"


if __name__ == "__main__":
    print("=" * 68)
    print("  LangChain 桥接层自测")
    print("=" * 68)
    test_langchain_available()
    test_retriever_returns_documents()
    test_two_paths_agree()
    test_langchain_answer()
    print("\n全部通过")
