FROM python:3.11-slim

WORKDIR /app

# 系统依赖（edge-tts 模式无需 torch/sox，仅保留基础网络库）
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Python 依赖（轻量：仅 edge-tts + FastAPI）
COPY requirements-lite.txt .
RUN pip install --no-cache-dir -r requirements-lite.txt

# 应用代码
COPY app.py .

EXPOSE 10096

HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD curl -f http://localhost:10096/health || exit 1

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "10096"]
