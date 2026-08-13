# syntax=docker/dockerfile:1
FROM python:3.11-slim

# 运行时通过 ASR_ENGINE 环境变量控制 ASR 引擎，SER_ENABLED 控制情感分析
# 镜像体积 ~2GB（含 torch+funasr），运行内存：
#   - ASR=dashscope + SER=on: ~1.5GB（仅 emotion2vec）
#   - ASR=funasr + SER=on: ~4GB（SenseVoice + emotion2vec）
#   - SER=off: ~200MB（仅 dashscope SDK 运行时）
# ⚡ 模型不打入镜像 — 通过 volume 从宿主机挂载（DA-10：假 ARG 已删，环境变量唯一驱动）

WORKDIR /app

# 系统依赖（soundfile 需要 libsndfile，funasr 需要 ffmpeg 转码）
# 修复：与 pip 一致启用 BuildKit --mount=type=cache + 阿里云镜像加速，
# 避免 ffmpeg(~200MB) 每次构建都从海外 snapshot.debian.org 重新拉取。
# 设计依据：design/04 §13.3（BuildKit pip 加速）— 系统包同等适用。
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt,sharing=locked \
    sed -i 's|http://deb.debian.org|https://mirrors.aliyun.com|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        libsndfile1 \
        ffmpeg

# Python 依赖 — 始终安装全量（torch + funasr + dashscope）
# 这样无论运行时 ASR_ENGINE 设什么，emotion2vec SER 都可用
COPY voice-service/requirements.txt ./
# torch/torchaudio 的 +cpu 变体以官方源为权威（第三方镜像缺版本/平台轮子，
# 如阿里云 pytorch-wheels 无 torchaudio 2.6.0+cpu；官方 CPU 源 ~200MB，其余依赖仍走阿里云主源）
RUN --mount=type=cache,target=/root/.cache/pip \
    pip config set global.index-url https://mirrors.aliyun.com/pypi/simple/ \
    && echo "📦 安装 torch + funasr + dashscope 全量依赖..." \
    && pip install torch==2.6.0+cpu torchaudio==2.6.0+cpu \
        --index-url https://download.pytorch.org/whl/cpu \
    && pip install -r requirements.txt

# ⚡ 模型不再在构建时下载 — 改为运行时从宿主机挂载
# 好处：
#   1. 镜像体积从 ~5GB 降至 ~2GB（funasr 模式）
#   2. 模型文件持久化在宿主机，重建容器无需重新下载
#   3. 通过 prepare-funasr.sh 脚本管理版本和更新
# 挂载点：/home/appuser/.cache/modelscope（由 docker-compose volumes 配置）

# 应用代码 + 配置 + 启动脚本（DA-01 核对：补齐 asr_engines.py；S-019：config_loader/metrics_common 移入 py-common 共享单源；OPS-002：补齐 ser_engines.py）
COPY voice-service/app.py voice-service/config.py voice-service/asr_engines.py voice-service/ser_engines.py voice-service/config.yaml voice-service/entrypoint.sh ./

# S-019（doing/93）：py-common 共享单源（config_loader/metrics_common，原双服务复制共享）
COPY py-common/ /app/py-common/
ENV PYTHONPATH=/app/py-common
RUN chmod +x entrypoint.sh

# 非 root 运行（审计 P3-31）
# 模型缓存挂载到 $HOME/.cache/modelscope（与 entrypoint.sh MODEL_BASE 对齐）
RUN useradd -m -u 1000 appuser
USER appuser

EXPOSE 10095

HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:10095/health')" || exit 1

ENTRYPOINT ["./entrypoint.sh"]
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "10095"]
