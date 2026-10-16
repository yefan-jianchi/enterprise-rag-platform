# ============================================================================
#  AI 服务镜像（Python 3.12 + FAISS）
#  FAISS 只有 CPU 版 wheel，镜像里装 faiss-cpu 即可，不需要 GPU 基础镜像
# ============================================================================

FROM python:3.12-slim

WORKDIR /app

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    TZ=Asia/Shanghai

# 健康检查工具 + 编译依赖（faiss/jieba 部分包需要）
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl build-essential \
    && rm -rf /var/lib/apt/lists/*

# 先装依赖，利用层缓存
COPY requirements.txt .
RUN pip install --upgrade pip -i https://pypi.tuna.tsinghua.edu.cn/simple \
    && pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple

# 再拷代码
COPY app ./app
COPY tests ./tests

# 索引与上传文件落地目录（compose 里挂卷持久化）
RUN mkdir -p /app/data/indexes /app/data/uploads
ENV DATA_DIR=/app/data

EXPOSE 8001

HEALTHCHECK --interval=20s --timeout=6s --start-period=30s --retries=5 \
    CMD curl -fs http://localhost:8001/health || exit 1

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001", "--workers", "2"]
