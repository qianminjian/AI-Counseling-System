#!/bin/bash
# verify-metrics-sync.sh — 校验 tts/voice 两服务 metrics_common.py 同步一致（doing/90 P-007）
# 背景：metrics_common 复制共享是显式决策（两服务独立发布不共享 wheel，文件头已声明），
# 本脚本防漂移——指标契约（计数器/summary 名）改动必须双点同步，CI 门禁拦截漏改。
set -euo pipefail

BASE="$(cd "$(dirname "$0")/.." && pwd)"
A="$BASE/tts-service/metrics_common.py"
B="$BASE/voice-service/metrics_common.py"

if ! diff -q "$A" "$B" >/dev/null; then
  echo "❌ metrics_common.py 两服务不同步（tts vs voice）——指标契约改动必须双点同步"
  diff "$A" "$B" | head -20
  exit 1
fi
echo "✅ metrics_common.py 同步一致"
