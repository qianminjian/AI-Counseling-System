# syntax=docker/dockerfile:1
FROM python:3.11-slim

# 构建参数：决定是否安装 FunASR 重型依赖（torch ~180MB + 模型 ~3GB）
# dashscope 模式仅需轻量 SDK（镜像 ~200MB，运行内存 <512MB）
# funasr 模式需完整依赖（镜像 ~2GB，运行内存 ~6GB）
# ⚡ 模型不再打入镜像 — 通过 volume 从宿主机挂载（见 docker-compose 和 prepare-funasr.sh）
ARG ASR_ENGINE=dashscope

WORKDIR /app

# 系统依赖（soundfile 需要 libsndfile）
RUN apt-get update && apt-get install -y --no-install-recommends \
    libsndfile1 \
    ffmpeg \
    && rm -rf /var/lib/apt/lists/*

# Python 依赖 — 按引擎模式分层安装
# 使用 BuildKit cache mount 加速 pip（宿主机缓存 wheel，重复构建秒级完成）
COPY requirements.txt requirements-dashscope.txt ./
RUN --mount=type=cache,target=/root/.cache/pip \
    pip config set global.index-url https://mirrors.aliyun.com/pypi/simple/ \
    && if [ "$ASR_ENGINE" = "funasr" ]; then \
        echo "📦 FunASR 模式：安装 torch + funasr 全量依赖..." \
        && pip install torch==2.6.0+cpu torchaudio==2.6.0+cpu \
            -f https://mirrors.aliyun.com/pytorch-wheels/cpu/ \
        && pip install -r requirements.txt; \
    else \
        echo "📦 DashScope 模式：仅安装轻量 SDK..." \
        && pip install -r requirements-dashscope.txt; \
    fi

# ⚡ 模型不再在构建时下载 — 改为运行时从宿主机挂载
# 好处：
#   1. 镜像体积从 ~5GB 降至 ~2GB（funasr 模式）
#   2. 模型文件持久化在宿主机，重建容器无需重新下载
#   3. 通过 prepare-funasr.sh 脚本管理版本和更新
# 挂载点：/root/.cache/modelscope（由 docker-compose volumes 配置）

# 应用代码 + 启动脚本
COPY app.py entrypoint.sh ./
RUN chmod +x entrypoint.sh

EXPOSE 10095

HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:10095/health')" || exit 1

ENTRYPOINT ["./entrypoint.sh"]
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "10095"]
