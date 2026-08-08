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
#   bash deploy/scripts/prepare-models.sh            # 下载缺失文件（已存在且校验和匹配则跳过）并生成 MANIFEST.sha256
#   bash deploy/scripts/prepare-models.sh --verify   # 只校验（manifest 比对 + 关键文件冒烟），不下载；deploy.sh 发布门禁用
#   MIRROR=https://hf-mirror.com bash ...            # 覆盖镜像源（默认 hf-mirror.com）
#
# 依赖：curl、python3、sha256sum/shasum（Linux/macOS 均可）
#
# ARCH-009 E-5：
# - 每次下载后生成 MANIFEST.sha256（<sha256>  <相对路径>，sha256sum -c 兼容）
# - 已存在文件按 manifest 校验和匹配才跳过（损坏文件自动重新下载）
# - --verify 模式供发布链路做完整性门禁（失败即红）：deploy.sh DEPLOY_STUDENT 上传前置校验（DA-06）

set -euo pipefail

VERIFY_ONLY=0
if [ "${1:-}" = "--verify" ]; then
    VERIFY_ONLY=1
fi

MIRROR="${MIRROR:-https://hf-mirror.com}"
PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TARGET_DIR="${PROJECT_ROOT}/frontend/student-h5/public/models"
MANIFEST="${TARGET_DIR}/MANIFEST.sha256"

# 哈希工具：Linux sha256sum / macOS shasum（两者 -c 校验格式兼容）
if command -v sha256sum >/dev/null 2>&1; then
    HASH_CMD="sha256sum"
else
    HASH_CMD="shasum -a 256"
fi

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

# 判断相对路径文件的校验和是否与 manifest 一致（用于跳过已完好文件）
file_hash_ok() {
    local rel="$1"
    [ -f "${MANIFEST}" ] || return 1
    local expected actual
    expected=$(awk -v p="  ./${rel}" '$0 ~ p "$" {print $1; exit}' "${MANIFEST}")
    [ -n "${expected}" ] || return 1
    actual=$(${HASH_CMD} "${TARGET_DIR}/${rel}" | awk '{print $1}')
    [ "${expected}" = "${actual}" ]
}

# 重新生成校验清单（排除自身及自身 .tmp，避免重定向先建文件被 find 扫描入清单），供 CI/发布链路 --verify 比对
write_manifest() {
    log "生成校验清单: MANIFEST.sha256"
    (cd "${TARGET_DIR}" && find . -type f ! -name 'MANIFEST.sha256' ! -name 'MANIFEST.sha256.tmp' -print0 | sort -z | xargs -0 ${HASH_CMD}) > "${MANIFEST}.tmp"
    mv "${MANIFEST}.tmp" "${MANIFEST}"
}

# ===== 校验模式（--verify）：manifest 比对 + 关键文件冒烟，不下载 =====
if [ "${VERIFY_ONLY}" -eq 1 ]; then
    log "===== 模型校验模式（--verify）====="
    FAIL=0
    if [ ! -s "${MANIFEST}" ]; then
        log "❌ 缺少 MANIFEST.sha256（先运行 prepare-models.sh 生成）"
        exit 1
    fi
    log "校验清单: ${MANIFEST}"
    if (cd "${TARGET_DIR}" && ${HASH_CMD} -c "${MANIFEST}" --quiet); then
        log "  ✅ 全部文件校验和一致"
    else
        log "  ❌ 存在校验和不一致（文件损坏或缺失）"
        FAIL=1
    fi
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
        log "❌ 模型校验失败 —— 阻断发布"
        exit 1
    fi
    TOTAL_SIZE=$(du -sh "${TARGET_DIR}" | cut -f1)
    log "✅ 模型校验通过（共 ${TOTAL_SIZE}）"
    exit 0
fi

# 模型清单：repo_id → 本地目录名
MODELS=(
    "onnx-community/whisper-tiny"
    "onnx-community/wespeaker-voxceleb-resnet34-LM"
)

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
        # ARCH-009 E-5：已存在且校验和匹配才跳过（损坏文件自动重新下载）
        if [ -s "${out}" ] && file_hash_ok "${f}"; then
            skipped=$((skipped + 1))
            continue
        fi
        [ -f "${out}" ] && rm -f "${out}"
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

# ARCH-009 E-5：投放完成后生成校验清单（发布链路 --verify 门禁依据）
write_manifest

TOTAL_SIZE=$(du -sh "${TARGET_DIR}" | cut -f1)
log "===== 模型投放完成（共 ${TOTAL_SIZE}），下一步：重新构建 student-h5 并发布 ====="
