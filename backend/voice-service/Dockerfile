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
COPY requirements.txt ./
RUN --mount=type=cache,target=/root/.cache/pip \
    pip config set global.index-url https://mirrors.aliyun.com/pypi/simple/ \
    && echo "📦 安装 torch + funasr + dashscope 全量依赖..." \
    && pip install torch==2.6.0+cpu torchaudio==2.6.0+cpu \
        -f https://mirrors.aliyun.com/pytorch-wheels/cpu/ \
    && pip install -r requirements.txt

# ⚡ 模型不再在构建时下载 — 改为运行时从宿主机挂载
# 好处：
#   1. 镜像体积从 ~5GB 降至 ~2GB（funasr 模式）
#   2. 模型文件持久化在宿主机，重建容器无需重新下载
#   3. 通过 prepare-funasr.sh 脚本管理版本和更新
# 挂载点：/home/appuser/.cache/modelscope（由 docker-compose volumes 配置）

# 应用代码 + 配置 + 启动脚本（DA-01 核对：补齐 asr_engines.py；DA-03 新增 metrics_common.py，
# 此前显式清单漏拷任一模块 → 发布必然 ModuleNotFoundError）
COPY app.py config.py config_loader.py asr_engines.py metrics_common.py config.yaml entrypoint.sh ./
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
