#!/usr/bin/env bash
# scripts/verify-config-passthrough.sh —— DC-003 配置透传契约校验
#
# 声明/透传/生效三处对齐：.env.example 是 TTS 运行时变量的唯一登记处（声明），
# 本脚本断言每个已登记变量都透传进两个 compose（docker-compose.yml / docker-compose.prod.yml）
# 的 tts-service environment（透传）——任一缺漏即退出 1。
#
# 运行：bash scripts/verify-config-passthrough.sh   （退出码 0=全绿；无 docker 依赖）

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_EXAMPLE="$ROOT/deploy/.env.example"
COMPOSE_DEV="$ROOT/deploy/docker-compose.yml"
COMPOSE_PROD="$ROOT/deploy/docker-compose.prod.yml"

FAILURES=0
ok()   { echo "ok   - $1"; }
fail() { echo "FAIL - $1"; FAILURES=$((FAILURES + 1)); }

# ===== 1. 提取 .env.example 中 TTS 相关变量名（DASHSCOPE_/TTS_ 前缀，声明清单） =====
VARS="$(grep -oE '^(DASHSCOPE_|TTS_)[A-Z_]+' "$ENV_EXAMPLE" | sort -u)"
if [ -z "$VARS" ]; then
    fail ".env.example 未登记任何 TTS 变量（DASHSCOPE_/TTS_ 前缀）"
    exit 1
fi

# ===== 2. 断言每个变量均透传到两个 compose 的 tts-service environment =====
for var in $VARS; do
    for compose in "$COMPOSE_DEV" "$COMPOSE_PROD"; do
        name="$(basename "$compose")"
        if grep -qE "^[[:space:]]*${var}:" "$compose"; then
            ok "${var} → ${name} 已透传"
        else
            fail "${var} 未透传到 ${name} 的 tts-service environment"
        fi
    done
done

echo ""
if [ "$FAILURES" -eq 0 ]; then
    echo "全部通过 ✓（.env.example 登记 $(echo "$VARS" | wc -l | tr -d ' ') 个变量与两 compose 透传一致）"
else
    echo "${FAILURES} 项失败 ✗——请在 deploy/.env.example 与两个 compose 中补齐透传"
fi
exit "$FAILURES"
