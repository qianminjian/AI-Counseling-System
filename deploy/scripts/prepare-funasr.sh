#!/bin/bash
# ================================================================
# prepare-funasr.sh — FunASR 模型 & torch 预下载脚本
# ================================================================
# 功能：在部署主机上预下载 FunASR 模型和 pip 依赖，避免每次构建/启动时重复下载
# 逻辑：
#   1. 校验本地缓存中模型目录是否存在
#   2. 缺失 → 下载；齐全 → 跳过，直接使用
#   3. 下载失败 → 非零退出（fail-fast，部署不得静默降级）
#
# 用法：
#   bash deploy/scripts/prepare-funasr.sh [--force]
#   --force: 强制重新下载
#
# 前置条件：
#   - 部署主机已安装 Docker（用临时容器下载，无需主机装 Python）
#   - 网络可达 ModelScope / PyPI 镜像源
# ================================================================

set -euo pipefail

# ===== 配置 =====
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}/../.."
DEPLOY_DIR="${SCRIPT_DIR}/.."

# 缓存目录（可通过环境变量覆盖，默认在 deploy 目录同级）
CACHE_BASE="${FUNASR_CACHE_DIR:-/guju/mindsafe/cache}"
MODEL_CACHE="${CACHE_BASE}/modelscope"
PIP_CACHE="${CACHE_BASE}/pip"
MANIFEST="${CACHE_BASE}/manifest.json"

# 需要缓存的模型（存在性校验对象；更新模型时改这里）
MODEL_IDS=(
    "iic/SenseVoiceSmall"
    "iic/emotion2vec_plus_large"
    "damo/fsmn-vad"
)

# pip 依赖版本
TORCH_VERSION="2.6.0+cpu"
FUNASR_VERSION="1.2.6"

# PyPI 镜像
PIP_INDEX="https://mirrors.aliyun.com/pypi/simple/"
TORCH_INDEX="https://mirrors.aliyun.com/pytorch-wheels/cpu/"

# ===== 辅助函数 =====

log() { echo "[$(date '+%H:%M:%S')] $*"; }
log_ok() { log "✅ $*"; }
log_skip() { log "⏭️  $*"; }
log_warn() { log "⚠️  $*"; }
log_err() { log "❌ $*" >&2; }

ensure_dir() {
    mkdir -p "$1"
    log "目录就绪: $1"
}

# 写入/更新 manifest
update_manifest() {
    python3 -c "
import json, datetime

manifest_path = '$MANIFEST'
try:
    with open(manifest_path) as f:
        m = json.load(f)
except:
    m = {'models': {}, 'pip': {}}

# 更新模型就绪标记
models = {
$(for model in "${MODEL_IDS[@]}"; do
    echo "    '$model': {'ready': True},"
done)
}
m['models'] = models

# 更新 pip 信息
m['pip'] = {
    'torch': '$TORCH_VERSION',
    'funasr': '$FUNASR_VERSION'
}

m['last_updated'] = datetime.datetime.now().isoformat()
m['host'] = '$(hostname)'

with open(manifest_path, 'w') as f:
    json.dump(m, f, indent=2, ensure_ascii=False)
print('manifest 已更新')
"
}

# ===== 主逻辑 =====

FORCE=false
[[ "${1:-}" == "--force" ]] && FORCE=true

log "=========================================="
log "FunASR 模型 & 依赖预下载"
log "=========================================="
log "缓存目录: $CACHE_BASE"
log "强制模式: $FORCE"
echo ""

# 1. 确保目录结构
ensure_dir "$MODEL_CACHE"
ensure_dir "$PIP_CACHE"

# 2. 缓存存在性校验
NEED_UPDATE=false

if [[ "$FORCE" == "true" ]]; then
    log "强制模式：全量下载"
    NEED_UPDATE=true
else
    # 检查模型文件是否实际存在（缺失 → 触发下载）
    for model in "${MODEL_IDS[@]}"; do
        model_dir="$MODEL_CACHE/hub/$model"
        if [[ ! -d "$model_dir" ]]; then
            log "模型目录缺失: $model_dir"
            NEED_UPDATE=true
            break
        fi
    done
fi

# 3. 执行下载（使用临时 Docker 容器，无需主机安装 Python 环境）
if [[ "$NEED_UPDATE" == "true" ]]; then
    log ""
    log "===== 开始下载 ====="
    log "使用 Docker 临时容器下载模型和依赖..."
    log "（首次下载约需 10-20 分钟，取决于网络速度）"
    echo ""

    # 构建下载脚本
    DOWNLOAD_SCRIPT=$(cat << 'PYTHON_EOF'
import os, sys, json, time

print("=" * 50)
print("FunASR 模型下载器（容器内执行）")
print("=" * 50)

# 设置 ModelScope 缓存路径（对应宿主机挂载目录）
os.environ["MODELSCOPE_CACHE"] = "/cache/modelscope"

start = time.time()

# 下载模型
print("\n[1/3] 下载 SenseVoiceSmall（ASR 模型）...")
from funasr import AutoModel
asr = AutoModel(model="iic/SenseVoiceSmall", vad_model="fsmn-vad", device="cpu")
print(f"  ✅ SenseVoiceSmall 就绪 ({time.time()-start:.0f}s)")

print("\n[2/3] 下载 emotion2vec_plus_large（情感模型）...")
t2 = time.time()
emo = AutoModel(model="iic/emotion2vec_plus_large", device="cpu")
print(f"  ✅ emotion2vec_plus_large 就绪 ({time.time()-t2:.0f}s)")

print("\n[3/3] 验证模型加载...")
print(f"  ASR 模型类型: {type(asr)}")
print(f"  SER 模型类型: {type(emo)}")

elapsed = time.time() - start
print(f"\n{'=' * 50}")
print(f"✅ 全部模型下载完成！耗时 {elapsed:.0f}s")
print(f"{'=' * 50}")
PYTHON_EOF
    )

    # 用 Docker 临时容器执行下载
    # - 挂载 modelscope 缓存目录（模型持久化到宿主机）
    # - 挂载 pip 缓存目录（加速 pip install）
    # - 使用与生产相同的基础镜像
    docker run --rm \
        -v "${MODEL_CACHE}:/cache/modelscope" \
        -v "${PIP_CACHE}:/root/.cache/pip" \
        -e "MODELSCOPE_CACHE=/cache/modelscope" \
        python:3.11-slim \
        bash -c "
            set -e
            echo '📦 安装系统依赖...'
            apt-get update -qq && apt-get install -y -qq libsndfile1 > /dev/null 2>&1

            echo '📦 安装 pip 依赖（torch + funasr）...'
            pip config set global.index-url $PIP_INDEX > /dev/null
            pip install --quiet torch==${TORCH_VERSION} torchaudio==2.6.0+cpu -f $TORCH_INDEX
            pip install --quiet funasr>=${FUNASR_VERSION}

            echo '📦 下载模型...'
            python3 -c \"$DOWNLOAD_SCRIPT\"
        "

    if [[ $? -eq 0 ]]; then
        log_ok "模型下载完成"
        update_manifest
    else
        log_err "模型下载失败！"
        exit 1
    fi
else
    log_skip "模型缓存已就绪，无需下载"
    log "  已缓存模型:"
    for model in "${MODEL_IDS[@]}"; do
        echo "    - $model"
    done
fi

# 4. 显示缓存状态
echo ""
log "=========================================="
log "缓存状态"
log "=========================================="
log "模型缓存: $(du -sh "$MODEL_CACHE" 2>/dev/null | cut -f1 || echo 'N/A')"
log "Pip 缓存: $(du -sh "$PIP_CACHE" 2>/dev/null | cut -f1 || echo 'N/A')"
if [[ -f "$MANIFEST" ]]; then
    log "Manifest: $MANIFEST"
    cat "$MANIFEST"
fi
echo ""
log_ok "预下载完成。部署时 voice-service 将直接挂载使用缓存模型。"
