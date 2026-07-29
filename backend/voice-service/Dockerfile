FROM python:3.11-slim

WORKDIR /app

# 系统依赖（soundfile 需要 libsndfile）
RUN apt-get update && apt-get install -y --no-install-recommends \
    libsndfile1 \
    ffmpeg \
    && rm -rf /var/lib/apt/lists/*

# Python 依赖（统一走阿里云镜像，download.pytorch.org 境内约 20KB/s 会导致构建数小时）
# 先装 CPU 版 torch（本机无 GPU），避免默认拉取 CUDA 版多占 ~2GB 内存导致 OOM
COPY requirements.txt .
RUN pip config set global.index-url https://mirrors.aliyun.com/pypi/simple/ \
    && pip install --no-cache-dir torch==2.6.0+cpu torchaudio==2.6.0+cpu \
        -f https://mirrors.aliyun.com/pytorch-wheels/cpu/ \
    && pip install --no-cache-dir -r requirements.txt

# 预下载模型（构建时缓存，避免每次启动重新下载）
RUN python -c "\
from funasr import AutoModel; \
AutoModel(model='iic/SenseVoiceSmall', vad_model='fsmn-vad', device='cpu'); \
AutoModel(model='iic/emotion2vec_plus_large', device='cpu'); \
print('Models cached successfully')"

# 应用代码
COPY app.py .

EXPOSE 10095

HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:10095/health')" || exit 1

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "10095"]
