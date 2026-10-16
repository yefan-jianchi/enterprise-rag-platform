"""
LangChain 编排桥接层
====================
本项目的手写检索链路（retrieval.py）性能可控、便于调参，但**企业里更常见的是用 LangChain 编排**。
所以这里做一层桥接，把自研能力接进 LangChain 生态：

    ┌──────────────────────────────────────────────────────────┐
    │  RagRetriever(BaseRetriever)                             │
    │    └── 内部调用自研混合检索（向量 + BM25 + RRF + 重排）    │
    │        暴露为 LangChain 标准 Retriever                    │
    ├──────────────────────────────────────────────────────────┤
    │  MockChatModel(BaseChatModel)                            │
    │    └── 把离线 Mock 模型包装成 LangChain ChatModel，        │
    │        让整条链在没有 API Key 时也能跑                     │
    ├──────────────────────────────────────────────────────────┤
    │  build_rag_chain() / answer_with_langchain()              │
    │    └── LCEL：prompt | llm | StrOutputParser               │
    └──────────────────────────────────────────────────────────┘

**面试可以这么讲**：同一个 RAG，手写链路和 LangChain 编排我都实现了。
手写版能精确控制 RRF、阈值、分数归一化；LangChain 版接入成本低、生态组件多。
两者共用同一套索引与检索器，所以效果一致——这也反过来证明我理解 LangChain 在做什么。
"""
from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional, Sequence

from app.config import get_settings
from app.core.retrieval import get_service
from app.schemas import RetrieveRequest

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# LangChain 是可选依赖：没装也不影响主链路，只是 /langchain/* 接口不可用
# ---------------------------------------------------------------------------
try:
    from langchain_core.callbacks import CallbackManagerForRetrieverRun
    from langchain_core.documents import Document
    from langchain_core.language_models import BaseChatModel
    from langchain_core.messages import AIMessage, BaseMessage
    from langchain_core.output_parsers import StrOutputParser
    from langchain_core.outputs import ChatGeneration, ChatResult
    from langchain_core.prompts import ChatPromptTemplate
    from langchain_core.retrievers import BaseRetriever
    from langchain_core.runnables import RunnablePassthrough

    LANGCHAIN_AVAILABLE = True
    LANGCHAIN_IMPORT_ERROR = ""
except Exception as _exc:  # pragma: no cover
    LANGCHAIN_AVAILABLE = False
    LANGCHAIN_IMPORT_ERROR = str(_exc)
    logger.warning("LangChain 不可用，/langchain/* 接口将返回 501：%s", _exc)

# 系统提示词：与手写链路保持一致，保证两条路径答案风格一致
SYSTEM_PROMPT = (
    "你是严谨的企业知识库助手，只依据【参考资料】回答，不要编造。\n"
    "资料不足时明确说明「知识库中没有相关内容」并建议转人工。\n"
    "回答末尾用 [1][2] 标注引用来源编号。"
)
HUMAN_TEMPLATE = "【参考资料】\n{context}\n\n【问题】\n{question}"


if LANGCHAIN_AVAILABLE:

    class RagRetriever(BaseRetriever):
        """
        把自研混合检索接成 LangChain Retriever。
        之后就能直接塞进 LCEL 链、Agent、或任何接受 Retriever 的组件里。
        """

        kb_id: int
        top_k: int = 5
        strategy: str = "HYBRID_RERANK"

        def _get_relevant_documents(
            self,
            query: str,
            *,
            run_manager: Optional[CallbackManagerForRetrieverRun] = None,
        ) -> List[Document]:
            svc = get_service(self.kb_id)
            resp = svc.retrieve(
                RetrieveRequest(kb_id=self.kb_id, query=query, strategy=self.strategy, top_k=self.top_k)
            )
            docs: List[Document] = []
            for r in resp.results:
                docs.append(
                    Document(
                        page_content=r.content,
                        metadata={
                            "chunk_id": r.chunk_id,
                            "doc_id": r.doc_id,
                            "doc_name": r.doc_name,
                            "page_no": r.page_no,
                            "chunk_index": r.chunk_index,
                            "score": r.score,
                            "source": f"{r.doc_name}#p{r.page_no}",
                        },
                    )
                )
            return docs

    class MockChatModel(BaseChatModel):
        """
        把项目的离线 Mock 模型包装成 LangChain ChatModel。
        这样即使不配置任何 API Key，LCEL 链也能完整跑通（便于演示与单测）。
        """

        @property
        def _llm_type(self) -> str:
            return "rag-platform-mock-chat"

        def _generate(
            self,
            messages: List[BaseMessage],
            stop: Optional[List[str]] = None,
            run_manager: Any = None,
            **kwargs: Any,
        ) -> ChatResult:
            from app.core.llm import MockLLM

            role_map = {"system": "system", "human": "user", "ai": "assistant"}
            payload = [
                {"role": role_map.get(getattr(m, "type", "human"), "user"), "content": m.content}
                for m in messages
            ]
            res = MockLLM().complete(payload, task="chat")
            return ChatResult(generations=[ChatGeneration(message=AIMessage(content=res.content))])


def _build_chat_model(provider: Optional[str] = None):
    """
    选模型：
      * 配了真实 Key 的提供方 → 用 langchain_openai.ChatOpenAI（DeepSeek/通义都是 OpenAI 兼容协议）
      * 否则 → MockChatModel，保证离线可用
    """
    s = get_settings()
    provider = (provider or s.llm_provider).lower()

    if provider == "deepseek" and s.deepseek_api_key:
        from langchain_openai import ChatOpenAI

        return ChatOpenAI(model=s.deepseek_chat_model, base_url=s.deepseek_base_url,
                          api_key=s.deepseek_api_key, temperature=s.llm_temperature)
    if provider == "dashscope" and s.dashscope_api_key:
        from langchain_openai import ChatOpenAI

        return ChatOpenAI(model=s.dashscope_chat_model, base_url=s.dashscope_base_url,
                          api_key=s.dashscope_api_key, temperature=s.llm_temperature)
    if provider == "openai" and s.openai_api_key:
        from langchain_openai import ChatOpenAI

        return ChatOpenAI(model=s.openai_chat_model, base_url=s.openai_base_url,
                          api_key=s.openai_api_key, temperature=s.llm_temperature)
    return MockChatModel()


def format_docs(docs: Sequence[Document]) -> str:
    """把检索结果拼成带编号的上下文，编号与 [1][2] 引用对应。"""
    parts: List[str] = []
    for i, d in enumerate(docs, start=1):
        page = d.metadata.get("page_no")
        head = f"[{i}] {d.metadata.get('doc_name', '未知文档')}"
        if page:
            head += f" 第{page}页"
        parts.append(f"{head}\n{d.page_content}")
    return "\n\n".join(parts)


def build_rag_chain(kb_id: int, top_k: Optional[int] = None,
                    strategy: str = "HYBRID_RERANK", provider: Optional[str] = None):
    """
    用 LCEL 拼一条标准 RAG 链：
        {context: retriever|format, question: passthrough} | prompt | llm | parser
    返回 (chain, retriever)，retriever 单独返回是为了拿到引用来源做溯源。
    """
    if not LANGCHAIN_AVAILABLE:
        raise RuntimeError(f"LangChain 不可用：{LANGCHAIN_IMPORT_ERROR}")

    s = get_settings()
    retriever = RagRetriever(kb_id=kb_id, top_k=top_k or s.top_k, strategy=strategy)
    prompt = ChatPromptTemplate.from_messages([("system", SYSTEM_PROMPT), ("human", HUMAN_TEMPLATE)])
    chain = (
        {"context": retriever | format_docs, "question": RunnablePassthrough()}
        | prompt
        | _build_chat_model(provider)
        | StrOutputParser()
    )
    return chain, retriever


def answer_with_langchain(question: str, kb_id: Optional[int] = None,
                          top_k: Optional[int] = None, strategy: str = "HYBRID_RERANK",
                          provider: Optional[str] = None) -> Dict[str, Any]:
    """
    LangChain 版本的 RAG 问答。
    返回结构与手写版 /api/v1/rag/ask 对齐，便于前端复用引用卡片。
    """
    if not LANGCHAIN_AVAILABLE:
        raise RuntimeError(f"LangChain 不可用：{LANGCHAIN_IMPORT_ERROR}")

    if not kb_id:
        # 不带知识库时退化成纯模型对话
        chain = (
            ChatPromptTemplate.from_messages([("system", SYSTEM_PROMPT), ("human", "{question}")])
            | _build_chat_model(provider)
            | StrOutputParser()
        )
        return {"answer": chain.invoke({"question": question}), "documents": [], "engine": "langchain"}

    chain, retriever = build_rag_chain(kb_id, top_k=top_k, strategy=strategy, provider=provider)
    docs = retriever.invoke(question)
    answer = chain.invoke(question)
    return {"answer": answer, "documents": docs, "engine": "langchain"}


def langchain_status() -> Dict[str, Any]:
    """给 /health 用：报告 LangChain 是否可用。"""
    info: Dict[str, Any] = {"available": LANGCHAIN_AVAILABLE}
    if LANGCHAIN_AVAILABLE:
        try:
            import langchain

            info["version"] = getattr(langchain, "__version__", "unknown")
        except Exception:
            info["version"] = "unknown"
    else:
        info["error"] = LANGCHAIN_IMPORT_ERROR
    return info
