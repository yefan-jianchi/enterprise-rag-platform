"""
多模型统一客户端
================
目标：**一套代码调用 DeepSeek / 通义千问 / OpenAI**。

三家都提供 OpenAI 兼容接口，所以差异只在 base_url / api_key / model 三件事。
再叠加一个 MockLLM，做到「没有 Key 也能把 RAG 全链路跑通」。

对外只暴露两个方法：
    complete(messages, task=...)      同步一次性返回
    astream(messages, task=...)       异步流式（供 SSE 透传给前端）

task 参数是给 Mock 用的「意图提示」，真实模型不关心它。
"""
from __future__ import annotations

import json
import logging
import re
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import AsyncIterator, Dict, List, Optional

from app.config import get_settings
from app.core.tokenizer import cut

logger = logging.getLogger(__name__)


# ============================ 内置 Prompt 模板 ============================
# 生产环境从数据库 ai_prompt_template 读取（Java 侧渲染后通过 system_prompt 传入）
DEFAULT_TEMPLATES: Dict[str, str] = {
    "RAG_QA": (
        "你是企业知识库助手。请严格依据【参考资料】回答用户问题，不要编造。\n"
        "如果资料不足以回答，请明确说明「知识库中没有相关内容」，并建议转人工。\n"
        "回答末尾用 [1][2] 标注引用来源编号。\n\n"
        "【参考资料】\n{context}\n\n【用户问题】\n{question}"
    ),
    "CS_REPLY": (
        "你是电商客服助手，语气礼貌、简洁。可以调用工具查询订单、创建工单、转人工。\n\n"
        "【知识库参考】\n{context}\n\n【用户问题】\n{question}"
    ),
    "REWRITE": (
        "把用户口语化的问题改写成 3 条适合向量检索的查询，覆盖同义词与专业术语。\n"
        "只输出 3 行，不要编号，不要解释。\n\n用户问题：{question}"
    ),
    "HYDE": (
        "请针对下面的问题，写一段 100 字左右的「假设性答案」，就像它已经是知识库里的原文片段。\n"
        "不要分点，不要标题。\n\n问题：{question}"
    ),
    "RERANK": (
        "请给下面每个「候选片段」与「问题」的相关性打 0-10 分。\n"
        "只输出 JSON 数组，形如 [{{\"id\":1,\"score\":8}}]，不要任何多余文字。\n\n"
        "问题：{question}\n\n候选片段：\n{candidates}"
    ),
}


@dataclass
class ChatResult:
    content: str
    provider: str
    model: str
    prompt_tokens: int = 0
    completion_tokens: int = 0
    latency_ms: int = 0
    raw: Optional[dict] = None

    @property
    def total_tokens(self) -> int:
        return self.prompt_tokens + self.completion_tokens


class BaseLLM(ABC):
    provider: str = "base"
    model: str = ""

    @abstractmethod
    def complete(self, messages: List[dict], task: str = "chat", **kwargs) -> ChatResult:
        ...

    async def astream(self, messages: List[dict], task: str = "chat", **kwargs) -> AsyncIterator[str]:
        """默认实现：不支持真流式的提供方，退化成「一次性返回 + 分片吐字」。"""
        result = self.complete(messages, task=task, **kwargs)
        step = 8
        for i in range(0, len(result.content), step):
            yield result.content[i : i + step]
            import asyncio

            await asyncio.sleep(0.01)


class OpenAICompatLLM(BaseLLM):
    """OpenAI 兼容协议的通用实现（DeepSeek / 通义 / OpenAI 共用）。"""

    def __init__(self, provider: str, base_url: str, api_key: str, model: str,
                 temperature: float, max_tokens: int, timeout: int) -> None:
        if not api_key:
            raise ValueError(f"提供方 {provider} 缺少 API Key")
        import httpx

        self.provider = provider
        self.model = model
        self.temperature = temperature
        self.max_tokens = max_tokens
        self._base_url = base_url.rstrip("/")
        self._headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
        self._client = httpx.Client(base_url=self._base_url, headers=self._headers, timeout=timeout)
        self._timeout = timeout

    def _payload(self, messages: List[dict], stream: bool, **kw) -> dict:
        return {
            "model": kw.get("model") or self.model,
            "messages": messages,
            "temperature": kw.get("temperature", self.temperature),
            "max_tokens": kw.get("max_tokens", self.max_tokens),
            "stream": stream,
        }

    def complete(self, messages: List[dict], task: str = "chat", **kwargs) -> ChatResult:
        t0 = time.time()
        resp = self._client.post("/chat/completions", json=self._payload(messages, False, **kwargs))
        resp.raise_for_status()
        data = resp.json()
        usage = data.get("usage", {}) or {}
        return ChatResult(
            content=data["choices"][0]["message"].get("content") or "",
            provider=self.provider,
            model=data.get("model", self.model),
            prompt_tokens=usage.get("prompt_tokens", 0),
            completion_tokens=usage.get("completion_tokens", 0),
            latency_ms=int((time.time() - t0) * 1000),
            raw=data,
        )

    async def astream(self, messages: List[dict], task: str = "chat", **kwargs) -> AsyncIterator[str]:
        """真流式：解析 SSE 的 data: 行，逐 delta 吐给前端。"""
        import httpx

        async with httpx.AsyncClient(
            base_url=self._base_url, headers=self._headers, timeout=self._timeout
        ) as client:
            async with client.stream("POST", "/chat/completions",
                                     json=self._payload(messages, True, **kwargs)) as resp:
                resp.raise_for_status()
                async for line in resp.aiter_lines():
                    if not line or not line.startswith("data:"):
                        continue
                    payload = line[5:].strip()
                    if payload == "[DONE]":
                        break
                    try:
                        delta = json.loads(payload)["choices"][0].get("delta", {})
                    except Exception:
                        continue
                    piece = delta.get("content")
                    if piece:
                        yield piece


class MockLLM(BaseLLM):
    """
    离线模拟模型。
    =================
    不是「返回固定字符串」那么敷衍，而是真的按 Prompt 语义干活：
      * chat   —— 从上下文里挑出与问题最相关的句子，组装成带引用的回答
      * rewrite—— 基于分词做同义扩展，生成 3 条改写查询
      * hyde   —— 用问题关键词拼一段「假设性文档」
    这样离线演示时，RAG 的检索、引用、流式输出全都能看到真实效果。
    """

    provider = "mock"

    def __init__(self, model: str = "mock-chat") -> None:
        self.model = model

    # ---------- 工具方法 ----------
    @staticmethod
    def _extract_block(text: str, start_marker: str, end_marker: str) -> str:
        i = text.find(start_marker)
        if i < 0:
            return ""
        i += len(start_marker)
        j = text.find(end_marker, i)
        return text[i: (j if j > 0 else len(text))].strip()

    @staticmethod
    def _sentences(text: str) -> List[str]:
        parts = re.split(r"(?<=[。！？；\n])", text)
        return [p.strip() for p in parts if len(p.strip()) >= 8]

    @classmethod
    def _best_sentences(cls, question: str, context: str, limit: int = 4) -> List[str]:
        """按「问题词命中数」给句子打分，挑出最相关的几句。"""
        q_tokens = set(cut(question))
        scored = []
        for s in cls._sentences(context):
            s_tokens = set(cut(s))
            overlap = len(q_tokens & s_tokens)
            if overlap:
                scored.append((overlap, len(s), s))
        scored.sort(key=lambda x: (x[0], -x[1]), reverse=True)
        return [s for _, _, s in scored[:limit]]

    # ---------- 主入口 ----------
    def complete(self, messages: List[dict], task: str = "chat", **kwargs) -> ChatResult:
        t0 = time.time()
        user_text = "\n".join(m.get("content", "") for m in messages if m.get("role") == "user")
        system_text = "\n".join(m.get("content", "") for m in messages if m.get("role") == "system")
        full = f"{system_text}\n{user_text}"

        if task == "rewrite":
            content = self._do_rewrite(user_text)
        elif task == "hyde":
            content = self._do_hyde(user_text)
        else:
            content = self._do_chat(user_text)

        prompt_tokens = max(1, len(full) // 2)
        completion_tokens = max(1, len(content) // 2)
        return ChatResult(
            content=content,
            provider=self.provider,
            model=self.model,
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
            latency_ms=int((time.time() - t0) * 1000) + 30,  # 模拟一点延迟
        )

    def _do_chat(self, text: str) -> str:
        question = self._extract_block(text, "【用户问题】", "【参考资料】")
        if not question:
            question = self._extract_block(text, "问题：", "候选片段")
        if not question:
            question = text.strip().splitlines()[-1] if text.strip() else ""
        context = self._extract_block(text, "【参考资料】", "【用户问题】")
        if not context:
            context = self._extract_block(text, "【知识库参考】", "【用户问题】")

        if not context:
            return (
                f"（离线模拟回答）你问的是「{question[:40]}」。"
                "当前没有检索到知识库内容，因此不编造答案。"
                "如需真实模型回答，请在 .env 中配置 DEEPSEEK_API_KEY 并把 LLM_PROVIDER 改为 deepseek。"
            )

        picks = self._best_sentences(question, context)
        if not picks:
            picks = self._sentences(context)[:3]
        body = "\n".join(f"- {s}" for s in picks) or "- （上下文为空）"
        return (
            f"（离线模拟回答，检索增强已生效）\n\n"
            f"根据知识库内容，关于「{question[:40]}」：\n{body}\n\n"
            f"以上结论来自检索到的资料片段 [1][2]，可在右侧引用区查看原文与页码。"
        )

    def _do_rewrite(self, text: str) -> str:
        question = self._extract_block(text, "用户问题：", "\n") or text.strip()
        tokens = cut(question)
        core = " ".join(tokens[:6]) if tokens else question
        lines = [
            question.strip(),
            f"{core} 规定 说明 流程",
            f"{core} 怎么办 如何办理 条件",
        ]
        return "\n".join(dict.fromkeys(l for l in lines if l))

    def _do_hyde(self, text: str) -> str:
        question = self._extract_block(text, "问题：", "\n") or text.strip()
        tokens = cut(question)[:8]
        core = "、".join(tokens) if tokens else question
        return (
            f"关于{core}，学校/公司相关规定如下："
            f"办理时需要携带本人有效证件，前往指定窗口或线上系统提交申请；"
            f"审核通过后一般在 1-3 个工作日内生效，具体时效以官方通知为准。"
            f"如遇特殊情况，可联系客服或主管部门进一步咨询。"
        )


def get_llm(provider: Optional[str] = None) -> BaseLLM:
    """工厂方法：按配置返回模型客户端。"""
    s = get_settings()
    provider = (provider or s.llm_provider).lower()

    if provider == "mock":
        return MockLLM()
    if provider == "deepseek":
        return OpenAICompatLLM("deepseek", s.deepseek_base_url, s.deepseek_api_key,
                               s.deepseek_chat_model, s.llm_temperature, s.llm_max_tokens, s.llm_timeout)
    if provider == "dashscope":
        return OpenAICompatLLM("dashscope", s.dashscope_base_url, s.dashscope_api_key,
                               s.dashscope_chat_model, s.llm_temperature, s.llm_max_tokens, s.llm_timeout)
    if provider == "openai":
        return OpenAICompatLLM("openai", s.openai_base_url, s.openai_api_key,
                               s.openai_chat_model, s.llm_temperature, s.llm_max_tokens, s.llm_timeout)
    raise ValueError(f"未知的模型提供方: {provider}")


def render_template(code: str, **kwargs) -> str:
    """渲染内置模板（生产环境建议用 DB 里的模板）。"""
    tpl = DEFAULT_TEMPLATES.get(code, "")
    return tpl.format(**kwargs)
