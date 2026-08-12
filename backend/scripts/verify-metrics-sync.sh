#!/bin/bash
# verify-metrics-sync.sh — 校验 py-common 共享单源存在（doing/93 S-019：metrics_common/config_loader
# 已从双服务复制共享收敛为 backend/py-common 单源，双份 diff 门禁随之移除）
set -euo pipefail

BASE="$(cd "$(dirname "$0")/.." && pwd)"
SHARED="$BASE/py-common"

for f in metrics_common.py config_loader.py; do
  if [ ! -f "$SHARED/$f" ]; then
    echo "❌ py-common/$f 缺失——共享单源被删除或移动，tts/voice 两服务 import 将失败"
    exit 1
  fi
done
# 双服务本地不得再存在副本（防止复制共享回潮）
for svc in tts-service voice-service; do
  for f in metrics_common.py config_loader.py; do
    if [ -f "$BASE/$svc/$f" ]; then
      echo "❌ $svc/$f 本地副本存在——应统一走 py-common 共享单源（S-019）"
      exit 1
    fi
  done
done
echo "✅ py-common 共享单源完整（metrics_common/config_loader），双服务无本地副本"
