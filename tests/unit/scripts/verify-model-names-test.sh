#!/usr/bin/env bash
# ============================================================
# DA-09: scripts/verify-model-names.sh 行为测试
# 验证：config.yaml 模型名与 entrypoint/prepare-funasr/app.py 消费点一致
#   A. 真实仓库全绿（当前模型名一致，CI 上即防漂移门禁）
#   B. fixture 破坏性用例（改 config.yaml / 删消费点 → 退出非 0）
# 用法：bash tests/unit/scripts/verify-model-names-test.sh
# ============================================================
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")/../../.." && pwd)/scripts/verify-model-names.sh"
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' EXIT

PASS=0
FAIL=0
ok() { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

echo "== DA-09 verify-model-names 测试 =="

# ---- A. 真实仓库（脚本无 docker 依赖，仅 grep 文件） ----
[ -f "$SCRIPT" ] && ok "脚本存在" || bad "脚本缺失: $SCRIPT"

if bash "$SCRIPT" > /tmp/vmn-real.log 2>&1; then
  ok "真实仓库模型名消费点全绿（退出 0）"
  grep -q "全部通过" /tmp/vmn-real.log && ok "输出含全部通过结论" || bad "输出缺少通过结论"
else
  bad "真实仓库应退出 0（消费点已漂移，先修复再接线）"
fi

# ---- B. fixture：迷你仓库 ----
F="$TEST_ROOT/repo"
mkdir -p "$F/scripts" "$F/backend/voice-service" "$F/deploy/scripts"
cp "$SCRIPT" "$F/scripts/"

cat > "$F/backend/voice-service/config.yaml" <<'EOF'
asr:
  funasr_model: "iic/SenseVoiceSmall"
  vad_model: "fsmn-vad"
  dashscope_model: "paraformer-realtime-v2"
ser:
  model: "iic/emotion2vec_plus_large"
EOF

cat > "$F/backend/voice-service/entrypoint.sh" <<'EOF'
REQUIRED_MODELS=(
    "iic/SenseVoiceSmall"
    "iic/emotion2vec_plus_large"
)
EOF

cat > "$F/deploy/scripts/prepare-funasr.sh" <<'EOF'
MODEL_IDS=(
"iic/SenseVoiceSmall"
"iic/emotion2vec_plus_large"
"damo/fsmn-vad"
)
EOF

cat > "$F/backend/voice-service/app.py" <<'EOF'
_CONFIG = load_config()
asr_model = AutoModel(model=_CONFIG["asr"]["funasr_model"], vad_model=_CONFIG["asr"]["vad_model"], device="cpu")
health = {"asr_model": "SenseVoiceSmall", "ser_model": "emotion2vec_plus_large"}
EOF

cat > "$F/backend/voice-service/ser_engines.py" <<'EOF'
# OPS-016（doing/95）：ser.model 消费点在 ser_engines.py（S-017 重构后移出 app.py）
_CONFIG = load_config()
model = AutoModel(model=_CONFIG["ser"]["model"], device="cpu")
EOF

# B.1 完整 fixture → 退出 0
if (cd "$F" && bash scripts/verify-model-names.sh > /dev/null 2>&1); then
  ok "完整 fixture 退出 0"
else
  bad "完整 fixture 应退出 0"
fi

# B.2 改 config.yaml 模型名 → 消费点缺名 → 非 0 且指出
cat > "$F/backend/voice-service/config.yaml" <<'EOF'
asr:
  funasr_model: "iic/SenseVoiceNew"
  vad_model: "fsmn-vad"
  dashscope_model: "paraformer-realtime-v2"
ser:
  model: "iic/emotion2vec_plus_large"
EOF
OUT=$(cd "$F" && bash scripts/verify-model-names.sh 2>&1 || true)
echo "$OUT" | grep -q "SenseVoiceNew" && ok "新模型名被解析" || bad "新模型名未被解析"
echo "$OUT" | grep -q "entrypoint.sh 缺 SenseVoiceNew" \
  && ok "改 config.yaml 后缺消费点被指出（entrypoint）" || bad "改 config.yaml 后缺消费点未被指出"
if (cd "$F" && bash scripts/verify-model-names.sh > /dev/null 2>&1); then
  bad "模型名漂移应退出非 0"; else ok "模型名漂移退出非 0"; fi

# B.3 删消费点硬编码（config.yaml 未变）→ 非 0
cat > "$F/backend/voice-service/config.yaml" <<'EOF'
asr:
  funasr_model: "iic/SenseVoiceSmall"
  vad_model: "fsmn-vad"
  dashscope_model: "paraformer-realtime-v2"
ser:
  model: "iic/emotion2vec_plus_large"
EOF
grep -v 'SenseVoiceSmall' "$F/backend/voice-service/app.py" > "$F/backend/voice-service/app.py.tmp" && mv "$F/backend/voice-service/app.py.tmp" "$F/backend/voice-service/app.py"
OUT=$(cd "$F" && bash scripts/verify-model-names.sh 2>&1 || true)
echo "$OUT" | grep -q "app.py 缺 SenseVoiceSmall" && ok "删消费点被指出（app.py）" || bad "删消费点未被指出"
if (cd "$F" && bash scripts/verify-model-names.sh > /dev/null 2>&1); then
  bad "删消费点应退出非 0"; else ok "删消费点退出非 0"; fi

# B.4 config.yaml 缺失 → 非 0
rm "$F/backend/voice-service/config.yaml"
if (cd "$F" && bash scripts/verify-model-names.sh > /dev/null 2>&1); then
  bad "config.yaml 缺失应退出非 0"; else ok "config.yaml 缺失退出非 0"; fi

echo ""
echo "DA-09 测试结果：$PASS 通过 / $FAIL 失败"
[ "$FAIL" -eq 0 ]
