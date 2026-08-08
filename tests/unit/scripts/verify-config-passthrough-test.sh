#!/usr/bin/env bash
# ============================================================
# D-10: scripts/verify-config-passthrough.sh 行为测试
# 验证：声明(.env.example)/透传(两 compose)/生效(代码消费) 三处一致性契约
#   A. 真实仓库全绿（契约当前成立，CI 上即回归护栏）
#   B. fixture 破坏性用例（缺登记/缺透传/消费超清单 → 退出非 0）
# 用法：bash tests/unit/scripts/verify-config-passthrough-test.sh
# ============================================================
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")/../../.." && pwd)/scripts/verify-config-passthrough.sh"
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' EXIT

PASS=0
FAIL=0
ok() { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

echo "== D-10 verify-config-passthrough 测试 =="

# ---- A. 真实仓库（脚本无 docker 依赖，仅 grep 文件） ----
[ -f "$SCRIPT" ] && ok "脚本存在" || bad "脚本缺失: $SCRIPT"

if bash "$SCRIPT" > /tmp/vcp-real.log 2>&1; then
  ok "真实仓库契约全绿（退出 0）"
  grep -q "全部通过" /tmp/vcp-real.log && ok "输出含全部通过结论" || bad "输出缺少通过结论"
else
  bad "真实仓库契约应退出 0（契约已漂移，先修复再接线）"
fi

# ---- B. fixture：迷你仓库（脚本 SERVICE_VARS 硬编码 tts/voice 两服务） ----
F="$TEST_ROOT/repo"
mkdir -p "$F/scripts" "$F/deploy" "$F/backend/tts-service" "$F/backend/voice-service"
cp "$SCRIPT" "$F/scripts/"

cat > "$F/deploy/.env.example" <<'EOF'
DASHSCOPE_API_KEY=
DASHSCOPE_TTS_MODEL=
TTS_SYNTHESIZE_TIMEOUT=
TTS_CORS_ORIGINS=
ASR_ENGINE=
SER_ENABLED=
VOICE_PROCESS_TIMEOUT=
VOICE_ANALYZE_TIMEOUT=
VOICE_CORS_ORIGINS=
EOF

write_compose() {  # $1=compose 路径
  cat > "$1" <<'EOF'
services:
  tts-service:
    image: mock
    environment:
      DASHSCOPE_API_KEY: ${DASHSCOPE_API_KEY:-}
      DASHSCOPE_TTS_MODEL: ${DASHSCOPE_TTS_MODEL:-}
      TTS_SYNTHESIZE_TIMEOUT: ${TTS_SYNTHESIZE_TIMEOUT:-}
      TTS_CORS_ORIGINS: ${TTS_CORS_ORIGINS:-}
  voice-service:
    image: mock
    environment:
      ASR_ENGINE: ${ASR_ENGINE:-}
      SER_ENABLED: ${SER_ENABLED:-}
      DASHSCOPE_API_KEY: ${DASHSCOPE_API_KEY:-}
      VOICE_PROCESS_TIMEOUT: ${VOICE_PROCESS_TIMEOUT:-}
      VOICE_ANALYZE_TIMEOUT: ${VOICE_ANALYZE_TIMEOUT:-}
      VOICE_CORS_ORIGINS: ${VOICE_CORS_ORIGINS:-}
EOF
}
write_compose "$F/deploy/docker-compose.yml"
write_compose "$F/deploy/docker-compose.prod.yml"

cat > "$F/backend/tts-service/app.py" <<'EOF'
import os
os.getenv("DASHSCOPE_API_KEY")
os.getenv("DASHSCOPE_TTS_MODEL")
os.getenv("TTS_SYNTHESIZE_TIMEOUT")
os.getenv("TTS_CORS_ORIGINS")
EOF

cat > "$F/backend/voice-service/app.py" <<'EOF'
import os
os.getenv("ASR_ENGINE")
os.getenv("SER_ENABLED")
os.getenv("DASHSCOPE_API_KEY")
os.getenv("VOICE_PROCESS_TIMEOUT")
os.getenv("VOICE_ANALYZE_TIMEOUT")
os.getenv("VOICE_CORS_ORIGINS")
EOF

# B.1 完整 fixture → 退出 0
if (cd "$F" && bash scripts/verify-config-passthrough.sh > /dev/null 2>&1); then
  ok "完整 fixture 退出 0"
else
  bad "完整 fixture 应退出 0"
fi

# B.2 缺登记：.env.example 删除 TTS_SYNTHESIZE_TIMEOUT → 非 0 且指出变量
grep -v '^TTS_SYNTHESIZE_TIMEOUT=' "$F/deploy/.env.example" > "$F/deploy/.env.example.tmp" && mv "$F/deploy/.env.example.tmp" "$F/deploy/.env.example"
OUT=$(cd "$F" && bash scripts/verify-config-passthrough.sh 2>&1 || true)
echo "$OUT" | grep -q "TTS_SYNTHESIZE_TIMEOUT 未在 .env.example 登记" \
  && ok "缺登记被指出（TTS_SYNTHESIZE_TIMEOUT）" || bad "缺登记未被指出"
if (cd "$F" && bash scripts/verify-config-passthrough.sh > /dev/null 2>&1); then
  bad "缺登记应退出非 0"; else ok "缺登记退出非 0"; fi
echo "TTS_SYNTHESIZE_TIMEOUT=" >> "$F/deploy/.env.example"

# B.3 缺透传：prod compose 删除 VOICE_CORS_ORIGINS → 非 0 且指出
grep -v '^      VOICE_CORS_ORIGINS: ' "$F/deploy/docker-compose.prod.yml" > "$F/deploy/docker-compose.prod.yml.tmp" && mv "$F/deploy/docker-compose.prod.yml.tmp" "$F/deploy/docker-compose.prod.yml"
OUT=$(cd "$F" && bash scripts/verify-config-passthrough.sh 2>&1 || true)
echo "$OUT" | grep -q "VOICE_CORS_ORIGINS 未透传到 docker-compose.prod.yml" \
  && ok "缺透传被指出（prod voice-service）" || bad "缺透传未被指出"
if (cd "$F" && bash scripts/verify-config-passthrough.sh > /dev/null 2>&1); then
  bad "缺透传应退出非 0"; else ok "缺透传退出非 0"; fi

# B.4 代码消费超清单：app.py 消费 GHOST_VAR（未声明）→ 非 0 且指出
echo 'os.getenv("GHOST_VAR")' >> "$F/backend/voice-service/app.py"
OUT=$(cd "$F" && bash scripts/verify-config-passthrough.sh 2>&1 || true)
echo "$OUT" | grep -q "GHOST_VAR 被 voice-service 代码消费但不在声明清单" \
  && ok "消费超清单被指出（GHOST_VAR）" || bad "消费超清单未被指出"
if (cd "$F" && bash scripts/verify-config-passthrough.sh > /dev/null 2>&1); then
  bad "消费超清单应退出非 0"; else ok "消费超清单退出非 0"; fi

# B.5 反向校验：compose 透传未登记变量 ${UNREGISTERED} → 非 0
# 锚点用 VOICE_ANALYZE_TIMEOUT 行（B.3 已删 VOICE_CORS_ORIGINS 行）
grep -v '^GHOST_VAR' "$F/backend/voice-service/app.py" > "$F/backend/voice-service/app.py.tmp" && mv "$F/backend/voice-service/app.py.tmp" "$F/backend/voice-service/app.py"
grep -v '^      VOICE_ANALYZE_TIMEOUT: ' "$F/deploy/docker-compose.prod.yml" > "$F/deploy/docker-compose.prod.yml.tmp" && mv "$F/deploy/docker-compose.prod.yml.tmp" "$F/deploy/docker-compose.prod.yml"
cat >> "$F/deploy/docker-compose.prod.yml" <<'EOC'
      VOICE_ANALYZE_TIMEOUT: ${VOICE_ANALYZE_TIMEOUT:-}
      UNREGISTERED_VAR: ${UNREGISTERED_VAR:-}
EOC
OUT=$(cd "$F" && bash scripts/verify-config-passthrough.sh 2>&1 || true)
echo "$OUT" | grep -q "UNREGISTERED_VAR 在 docker-compose.prod.yml voice-service 透传但未在 .env.example 登记" \
  && ok "反向透传未登记被指出（UNREGISTERED_VAR）" || bad "反向透传未登记未被指出"
if (cd "$F" && bash scripts/verify-config-passthrough.sh > /dev/null 2>&1); then
  bad "反向未登记应退出非 0"; else ok "反向未登记退出非 0"; fi

echo ""
echo "D-10 测试结果：$PASS 通过 / $FAIL 失败"
[ "$FAIL" -eq 0 ]
