# syntax=docker/dockerfile:1
FROM python:3.11-slim

WORKDIR /app

# 系统依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Python 依赖（在线安装，阿里云镜像加速；与 voice-service 一致）
# 审计 P2-24：不再依赖 wheels/ 离线目录（避免 15MB 二进制入库与"忘刷新即构建失败"）
# 可选离线方案：本地跑 ./refresh-wheels.sh 生成 wheels/ 后手动同步，pip 加 --no-index --find-links
COPY requirements-lite.txt .
RUN --mount=type=cache,target=/root/.cache/pip \
    pip config set global.index-url https://mirrors.aliyun.com/pypi/simple/ \
    && pip install -r requirements-lite.txt

# 应用代码 + 配置文件（CFG-004：config.yaml 外置，修改无需重建镜像）
# DA-01：COPY *.py 覆盖 tts_engines.py/tts_policy.py（此前漏拷 → 发布必然 ModuleNotFoundError）
COPY *.py config.yaml ./

# 非 root 运行（审计 P3-31）
RUN useradd -m -u 1000 appuser
USER appuser

EXPOSE 10096

HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD curl -f http://localhost:10096/health || exit 1

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "10096"]
