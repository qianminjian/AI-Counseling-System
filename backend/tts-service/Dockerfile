# syntax=docker/dockerfile:1
FROM python:3.11-slim

WORKDIR /app

# 系统依赖（D-08 doing/98：对齐 voice-service 的 BuildKit cache mount + 阿里云镜像，design/04 §13.3；
# curl 体量小，但统一构建范式避免两服务漂移）
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt,sharing=locked \
    sed -i 's|http://deb.debian.org|https://mirrors.aliyun.com|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        curl

# Python 依赖（在线安装，阿里云镜像加速；与 voice-service 一致）
# 审计 P2-24：不再依赖 wheels/ 离线目录（避免 15MB 二进制入库与"忘刷新即构建失败"）
# F-03（doing/96）：refresh-wheels.sh 已删除（失实注释宣称 deploy.sh 自动 rsync wheels/，实态排除）
COPY tts-service/requirements-lite.txt .
RUN --mount=type=cache,target=/root/.cache/pip \
    pip config set global.index-url https://mirrors.aliyun.com/pypi/simple/ \
    && pip install -r requirements-lite.txt

# 应用代码 + 配置文件（CFG-004：config.yaml 外置，修改无需重建镜像）
# DA-01：COPY *.py 覆盖 tts_engines.py/tts_policy.py（此前漏拷 → 发布必然 ModuleNotFoundError）
COPY tts-service/*.py tts-service/config.yaml ./

# S-019（doing/93）：py-common 共享单源（config_loader/metrics_common，原双服务复制共享）
COPY py-common/ /app/py-common/
ENV PYTHONPATH=/app/py-common

# 非 root 运行（审计 P3-31）
RUN useradd -m -u 1000 appuser
USER appuser

EXPOSE 10096

HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD curl -f http://localhost:10096/health || exit 1

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "10096"]
