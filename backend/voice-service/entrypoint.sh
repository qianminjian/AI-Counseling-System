#!/bin/bash
# ================================================================
# voice-service 容器启动入口
# ================================================================
# 职责：
#   1. DashScope 模式 → 直接启动（无模型依赖）
#   2. FunASR 模式 → 校验模型文件是否已挂载就绪，未就绪则明确报错
# ================================================================

set -e

ASR_ENGINE="${ASR_ENGINE:-dashscope}"
# DA-15：SER_ENABLED 默认 true（与 app.py 默认值对齐）；false 时剔除 emotion2vec 模型检查。
# 大小写归一化后比较（与 app.py 的 .lower() 语义一致），TRUE/True 与 true 同判定
SER_ENABLED="$(printf '%s' "${SER_ENABLED:-true}" | tr '[:upper:]' '[:lower:]')"

if [ "$ASR_ENGINE" = "funasr" ]; then
    echo "🔍 FunASR 模式：检查模型文件..."

    # ModelScope 缓存路径（对应 docker-compose volume 挂载点；非 root 用户 HOME=/home/appuser）
    MODEL_BASE="${HOME}/.cache/modelscope/hub"

    # 必需模型：ASR 模型始终要求；SER 模型仅在 SER_ENABLED=true 时要求——
    # 显式禁用 SER（SER_ENABLED=false）时 emotion2vec 不应阻断部署（与 app.py 加载面一致）
    REQUIRED_MODELS=("iic/SenseVoiceSmall")
    if [ "$SER_ENABLED" = "true" ]; then
        REQUIRED_MODELS+=("iic/emotion2vec_plus_large")
    fi

    MISSING=()
    for model in "${REQUIRED_MODELS[@]}"; do
        model_dir="$MODEL_BASE/$model"
        if [ ! -d "$model_dir" ] || [ -z "$(ls -A "$model_dir" 2>/dev/null)" ]; then
            MISSING+=("$model")
        fi
    done

    if [ ${#MISSING[@]} -gt 0 ]; then
        echo ""
        echo "❌ FunASR 模型文件缺失！"
        echo ""
        echo "  缺失模型:"
        for m in "${MISSING[@]}"; do
            echo "    - $m"
        done
        echo ""
        echo "  解决方案："
        echo "    1. 在部署主机运行: bash deploy/scripts/prepare-funasr.sh"
        echo "    2. 确认 docker-compose.yml 中 voice-service 已挂载模型目录："
        echo "       volumes:"
        echo "         - \${MODEL_CACHE_DIR:-./cache/modelscope}:/home/appuser/.cache/modelscope"
        echo ""
        echo "  或切换为 DashScope 模式（无需本地模型）："
        echo "    ASR_ENGINE=dashscope"
        echo ""
        exit 1
    fi

    echo "✅ 模型文件就绪："
    for model in "${REQUIRED_MODELS[@]}"; do
        size=$(du -sh "$MODEL_BASE/$model" 2>/dev/null | cut -f1 || echo "?")
        echo "  - $model ($size)"
    done
    echo ""
fi

# 启动应用
exec "$@"
