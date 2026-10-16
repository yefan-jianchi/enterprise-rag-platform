"""
配置管理
========
所有可调参数集中在这里，支持三种来源（优先级从高到低）：
    1. 真实环境变量
    2. 项目根目录下的 .env 文件
    3. 代码里的默认值

这样做的意义：Docker/K8s 里用环境变量注入，本地开发用 .env，
不需要改任何代码就能切换模型提供方、索引目录、检索策略。
"""
from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# 项目根目录：ai-service/
BASE_DIR = Path(__file__).resolve().parent.parent


def _auto_data_dir() -> Path:
    """
    自动选择数据目录（索引 / 上传文件落地）。

    ⚠ 这里踩过一个真实的坑：**Windows 上 FAISS 的底层 C++ IO 用 ANSI 编码打开文件**，
    路径里只要出现中文（比如项目放在「桌面\\项目\\」下），
    faiss.write_index 就会报 “could not open ... No such file or directory”。
    所以当项目路径含非 ASCII 字符时，自动把数据目录挪到 LOCALAPPDATA（纯英文路径）。
    想固定目录就直接在 .env 里设 DATA_DIR。
    """
    project_data = BASE_DIR / "data"
    if str(project_data).isascii():
        return project_data
    base = os.environ.get("LOCALAPPDATA") or os.environ.get("TEMP") or str(Path.home())
    return Path(base) / "enterprise-rag-platform" / "data"


class Settings(BaseSettings):
    """全局配置对象（单例，见 get_settings）。"""

    model_config = SettingsConfigDict(
        env_file=str(BASE_DIR / ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # ---------------- 服务本身 ----------------
    app_name: str = "enterprise-rag-ai-service"
    host: str = "0.0.0.0"
    port: int = 8001
    log_level: str = "INFO"

    # ---------------- 数据落地 ----------------
    # 索引、分块元数据都放在这个目录，Docker 里挂卷持久化。
    # 留空 = 自动选择（项目路径含中文时自动降级到 LOCALAPPDATA，原因见 _auto_data_dir）
    data_dir: str = ""

    # ---------------- 向量化 ----------------
    # mock = 本地确定性向量（无需 Key，离线可跑，用于演示与 CI）
    # openai / dashscope / deepseek 走真实 Embedding 接口
    embed_provider: str = "mock"
    embed_model: str = "mock-embed-384"
    embed_dim: int = 384          # mock 向量维度，必须与 index 一致
    embed_batch_size: int = 32

    # ---------------- 大模型（OpenAI 兼容协议）----------------
    # 三家都用 OpenAI 兼容接口，所以只需要 base_url + api_key + model 三件套
    llm_provider: str = "mock"    # mock / deepseek / dashscope / openai
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com/v1"
    deepseek_chat_model: str = "deepseek-chat"

    dashscope_api_key: str = ""
    dashscope_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    dashscope_chat_model: str = "qwen-plus"

    openai_api_key: str = ""
    openai_base_url: str = "https://api.openai.com/v1"
    openai_chat_model: str = "gpt-4o-mini"

    llm_temperature: float = 0.3
    llm_max_tokens: int = 2048
    llm_timeout: int = 60

    # ---------------- 分块 ----------------
    chunk_size: int = 500         # 目标长度（按字符近似 token）
    chunk_overlap: int = 80       # 相邻块重叠，避免答案被切断
    min_chunk_size: int = 60      # 太短的块直接丢掉

    # ---------------- 检索 ----------------
    top_k: int = 5                # 最终返回条数
    candidate_k: int = 20         # 每路召回候选数
    rrf_k: int = 60               # RRF 平滑常数，业界常用 60
    score_threshold: float = 0.20 # 相似度低于此值视为不相关
    enable_rewrite: bool = True   # 查询改写
    enable_hyde: bool = True      # HyDE 假设文档
    enable_rerank: bool = True    # 重排

    @property
    def data_path(self) -> Path:
        p = Path(self.data_dir) if self.data_dir and self.data_dir.strip() else _auto_data_dir()
        p.mkdir(parents=True, exist_ok=True)
        return p

    @property
    def index_path(self) -> Path:
        p = self.data_path / "indexes"
        p.mkdir(parents=True, exist_ok=True)
        return p


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """带缓存的配置读取，全局只构造一次。"""
    return Settings()
