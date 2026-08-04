#!/usr/bin/env bash
# MindSafe 端侧模型投放脚本
# 用途：下载语音唤醒（whisper-tiny）与声纹识别（wespeaker）的 ONNX 模型，
#       投放到 frontend/student-h5/public/models/，Vite 构建后随 dist 发布，
#       由 nginx 在 /mindsafe/models/ 同源提供（前端 SAME_ORIGIN 模式依赖此目录）。
#
# 背景：前端配置 WAKE_MODEL_REMOTE_HOST / VP_MODEL_REMOTE_HOST = 'SAME_ORIGIN'，
#       若本脚本未执行，模型请求 404 → 唤醒/声纹功能加载失败。
#
# 用法：
#   bash deploy/scripts/prepare-models.sh            # 下载缺失文件（已存在则跳过）
#   MIRROR=https://hf-mirror.com bash ...            # 覆盖镜像源（默认 hf-mirror.com）
#
# 依赖：curl、python3（服务器/本机均可）

set -euo pipefail

MIRROR="${MIRROR:-https://hf-mirror.com}"
PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TARGET_DIR="${PROJECT_ROOT}/frontend/student-h5/public/models"

# 模型清单：repo_id → 本地目录名
MODELS=(
    "onnx-community/whisper-tiny"
    "onnx-community/wespeaker-voxceleb-resnet34-LM"
)

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

# 过滤规则：只保留 transformers.js 运行所需文件（json 配置 + 量化 ONNX），
# 排除未量化全精度模型（体积大且前端不加载）与无关文件
keep_file() {
    case "$1" in
        *.json) return 0 ;;
        *quantized*.onnx) return 0 ;;
        *.txt) return 0 ;;  # vocab 等小文件
        *) return 1 ;;
    esac
}

fetch_model() {
    local repo="$1"
    local dest="${TARGET_DIR}/${repo}"
    mkdir -p "${dest}"

    log "处理模型: ${repo}"
    local listing
    if ! listing=$(curl -fsSL "${MIRROR}/api/models/${repo}/tree/main?recursive=true"); then
        log "ERROR: 无法获取 ${repo} 文件清单（镜像源 ${MIRROR} 不可达？）"
        return 1
    fi

    local files
    files=$(echo "${listing}" | python3 -c "
import sys, json
for item in json.load(sys.stdin):
    if item.get('type') == 'file':
        print(item['path'])
")

    local missing=0 downloaded=0 skipped=0
    while IFS= read -r f; do
        [ -z "${f}" ] && continue
        keep_file "${f}" || continue
        local out="${dest}/${f}"
        if [ -s "${out}" ]; then
            skipped=$((skipped + 1))
            continue
        fi
        mkdir -p "$(dirname "${out}")"
        log "  下载 ${f}"
        if curl -fSL --retry 3 -o "${out}.tmp" "${MIRROR}/${repo}/resolve/main/${f}"; then
            mv "${out}.tmp" "${out}"
            downloaded=$((downloaded + 1))
        else
            rm -f "${out}.tmp"
            log "  ERROR: 下载失败 ${f}"
            missing=$((missing + 1))
        fi
    done <<< "${files}"

    log "  完成: 新下载 ${downloaded}，已存在 ${skipped}，失败 ${missing}"
    [ "${missing}" -eq 0 ]
}

log "===== 端侧模型投放开始 ====="
log "目标目录: ${TARGET_DIR}"
log "镜像源: ${MIRROR}"

FAIL=0
for repo in "${MODELS[@]}"; do
    fetch_model "${repo}" || FAIL=1
done

# ===== 投放后自检（启动告警）=====
log "===== 投放自检 ====="
check_file() {
    if [ -s "${TARGET_DIR}/$1" ]; then
        log "  ✅ $1"
    else
        log "  ❌ 缺失 $1 —— 前端 SAME_ORIGIN 加载将 404，唤醒/声纹功能不可用！"
        FAIL=1
    fi
}
check_file "onnx-community/whisper-tiny/config.json"
check_file "onnx-community/whisper-tiny/onnx/encoder_model_quantized.onnx"
check_file "onnx-community/whisper-tiny/onnx/decoder_model_merged_quantized.onnx"
check_file "onnx-community/wespeaker-voxceleb-resnet34-LM/config.json"

if [ "${FAIL}" -ne 0 ]; then
    log "⚠️  WARNING: 模型投放不完整，请勿发布前端（SAME_ORIGIN 模式下缺失文件导致功能失效）"
    exit 1
fi

TOTAL_SIZE=$(du -sh "${TARGET_DIR}" | cut -f1)
log "===== 模型投放完成（共 ${TOTAL_SIZE}），下一步：重新构建 student-h5 并发布 ====="
