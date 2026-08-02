FROM python:3.11-slim

WORKDIR /app

# 系统依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Python 依赖（离线安装，避免服务器网络慢）
COPY wheels/ /tmp/wheels/
COPY requirements-lite.txt .
RUN pip install --no-cache-dir --no-index --find-links=/tmp/wheels -r requirements-lite.txt \
    && rm -rf /tmp/wheels

# 应用代码 + 配置文件（CFG-004：config.yaml 外置，修改无需重建镜像）
COPY app.py config.yaml ./

EXPOSE 10096

HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD curl -f http://localhost:10096/health || exit 1

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "10096"]
