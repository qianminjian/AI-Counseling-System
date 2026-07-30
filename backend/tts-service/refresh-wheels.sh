#!/bin/bash
# 刷新 tts-service 离线 wheels（requirements-lite.txt 变更时执行）
# 用法：./refresh-wheels.sh
# 产出：./wheels/ 目录（随代码一起 rsync 到服务器）
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REQ="$SCRIPT_DIR/requirements-lite.txt"
OUT="$SCRIPT_DIR/wheels"

if [ ! -f "$REQ" ]; then
  echo "❌ 未找到 $REQ"
  exit 1
fi

echo "📦 下载 wheels（目标平台: linux x86_64, Python 3.11）..."
rm -rf "$OUT"
mkdir -p "$OUT"

pip3 download \
  -r "$REQ" \
  -d "$OUT" \
  --platform manylinux2014_x86_64 \
  --platform manylinux_2_17_x86_64 \
  --platform manylinux_2_28_x86_64 \
  --platform linux_x86_64 \
  --python-version 311 \
  --only-binary=:all:

COUNT=$(ls "$OUT"/*.whl 2>/dev/null | wc -l | tr -d ' ')
SIZE=$(du -sh "$OUT" | awk '{print $1}')
echo "✅ 完成：$COUNT 个包，共 $SIZE"
echo "   路径：$OUT"
echo ""
echo "下一步：./deploy.sh --tts  （会自动 rsync wheels/ 到服务器）"
