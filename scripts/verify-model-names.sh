#!/usr/bin/env bash
# scripts/verify-model-names.sh —— DA-09 模型名单一事实源校验（方案 A：CI 断言）
#
# config.yaml 为权威事实源，断言模型名出现在各消费点：
#   entrypoint.sh（REQUIRED_MODELS 启动校验目录）
#   deploy/scripts/prepare-funasr.sh（MODEL_IDS 下载清单）
#   app.py（health 展示名）
# 任一消费点与 config.yaml 不一致即退出 1（换模型防「entrypoint 校验误判/下载错模型」）。
# 主体名匹配（去 org 前缀）：兼容 damo/fsmn-vad 与 fsmn-vad 等组织前缀差异。
#
# 运行：bash scripts/verify-model-names.sh   （退出码 0=全绿；无 docker 依赖）

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG="$ROOT/backend/voice-service/config.yaml"
ENTRYPOINT="$ROOT/backend/voice-service/entrypoint.sh"
PREPARE="$ROOT/deploy/scripts/prepare-funasr.sh"
APP="$ROOT/backend/voice-service/app.py"

FAILURES=0
ok()   { echo "ok   - $1"; }
fail() { echo "FAIL - $1"; FAILURES=$((FAILURES + 1)); }

# ===== 1. 从 config.yaml 提取模型名（权威事实源） =====
# 形如：`  funasr_model: "iic/SenseVoiceSmall"` / `  model: "iic/emotion2vec_plus_large"`
extract() {  # $1=key
  grep -E "^\s+${1}:" "$CONFIG" | head -1 | sed -E 's/.*"([^"]+)".*/\1/'
}
FUNASR_MODEL="$(extract funasr_model)"
VAD_MODEL="$(extract vad_model)"
SER_MODEL="$(extract model)"

if [ -n "$FUNASR_MODEL" ] && [ -n "$VAD_MODEL" ] && [ -n "$SER_MODEL" ]; then
  ok "config.yaml 解析出 3 个模型名（${FUNASR_MODEL} / ${VAD_MODEL} / ${SER_MODEL}）"
else
  fail "config.yaml 模型名解析失败（funasr_model=${FUNASR_MODEL:-空} vad_model=${VAD_MODEL:-空} model=${SER_MODEL:-空}）"
fi

# 主体名（去 org 前缀：damo/fsmn-vad → fsmn-vad，兼容组织前缀差异）
base() { echo "$1" | sed -E 's#.*/##'; }
FB="$(base "$FUNASR_MODEL")"
VB="$(base "$VAD_MODEL")"
SB="$(base "$SER_MODEL")"

# ===== 2. 消费点断言（每个模型名必须在对应消费点出现） =====
echo "===== 模型 ${FB} / ${VB} / ${SB} 消费点核对 ====="

# 2.1 entrypoint.sh（启动校验 REQUIRED_MODELS）
grep -q "$FB" "$ENTRYPOINT" && ok "entrypoint.sh 含 ${FB}（启动校验）" || fail "entrypoint.sh 缺 ${FB}"
grep -q "$SB" "$ENTRYPOINT" && ok "entrypoint.sh 含 ${SB}（启动校验）" || fail "entrypoint.sh 缺 ${SB}"

# 2.2 prepare-funasr.sh（MODEL_IDS 下载清单）
grep -q "$FB" "$PREPARE" && ok "prepare-funasr.sh 含 ${FB}（下载清单）" || fail "prepare-funasr.sh 缺 ${FB}"
grep -q "$VB" "$PREPARE" && ok "prepare-funasr.sh 含 ${VB}（下载清单）" || fail "prepare-funasr.sh 缺 ${VB}"
grep -q "$SB" "$PREPARE" && ok "prepare-funasr.sh 含 ${SB}（下载清单）" || fail "prepare-funasr.sh 缺 ${SB}"

# 2.3 app.py（health 展示名）
grep -q "$FB" "$APP" && ok "app.py 含 ${FB}（health 展示）" || fail "app.py 缺 ${FB}"
grep -q "$SB" "$APP" && ok "app.py 含 ${SB}（health 展示）" || fail "app.py 缺 ${SB}"

# 2.4 app.py/ser_engines.py 运行时消费（配置未失效）：funasr/vad 引擎加载键在 app.py，
#     ser.model 在 ser_engines.py（OPS-016，doing/95：S-017 重构后 SER 消费点移出 app.py，脚本同步）
grep -q '"asr"\]\["funasr_model"' "$APP" && ok "app.py 运行时消费 funasr_model（config.yaml 驱动）" || fail "app.py 未消费 funasr_model（配置失效）"
grep -q '"asr"\]\["vad_model"' "$APP" && ok "app.py 运行时消费 vad_model（config.yaml 驱动）" || fail "app.py 未消费 vad_model（配置失效）"
grep -q '"ser"\]\["model"' "$ROOT/backend/voice-service/ser_engines.py" && ok "ser_engines.py 运行时消费 ser.model（config.yaml 驱动）" || fail "ser_engines.py 未消费 ser.model（配置失效）"

echo ""
if [ "$FAILURES" -eq 0 ]; then
  echo "全部通过 ✓（config.yaml 模型名与 entrypoint/prepare-funasr/app.py 消费点一致）"
else
  echo "${FAILURES} 项失败 ✗——请按 FAIL 提示同步消费点（或确认 config.yaml 变更是否有意）"
fi
exit "$FAILURES"
