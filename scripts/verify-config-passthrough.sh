#!/usr/bin/env bash
# scripts/verify-config-passthrough.sh —— DC-003 + D3 配置透传契约校验
#
# 声明/透传/生效三处对齐（按服务声明变量清单，替代前缀白名单）：
#   1. 声明：.env.example 登记（每个清单变量必须已登记）
#   2. 透传：两个 compose（docker-compose.yml / docker-compose.prod.yml）对应 service
#      environment 段必须透传每个清单变量（per-service 校验，替代全文件 grep）
#   3. 生效：服务代码 os.environ/os.getenv 消费的变量必须 ⊆ 声明清单
#   反向：compose environment 段透传的 ${VAR} 必须已在 .env.example 登记
# 任一缺漏即退出 1。
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

# ===== 1. 服务变量清单（D3：显式声明，替代 DASHSCOPE_/TTS_ 前缀白名单） =====
# 格式：SERVICE:变量1,变量2,...（须与 .env.example 登记、compose 透传、代码消费三方一致；
# 服务目录名 = 服务名，即 backend/<SERVICE>/*.py）
SERVICE_VARS=(
  "tts-service:DASHSCOPE_API_KEY,DASHSCOPE_TTS_MODEL,TTS_SYNTHESIZE_TIMEOUT,TTS_CORS_ORIGINS"
  "voice-service:ASR_ENGINE,SER_ENABLED,DASHSCOPE_API_KEY,VOICE_PROCESS_TIMEOUT,VOICE_ANALYZE_TIMEOUT,VOICE_CORS_ORIGINS"
)

# ===== 2. 提取 compose 中指定 service 的 environment 块（awk：顶格 service 边界 + 缩进深度） =====
# 输出该块的 KEY: 行（含 ${VAR} 透传行与注释外行）；environment 同级缩进即块结束
service_env_block() {  # $1=compose 路径 $2=service 名
  awk -v svc="$2" '
    $0 ~ "^[[:space:]]*" svc ":" {
      in_svc=1; in_env=0
      match($0, /^[[:space:]]*/); svc_indent=RLENGTH
      next
    }
    in_svc {
      match($0, /^[[:space:]]*/); indent=RLENGTH
      if (indent <= svc_indent) { in_svc=0; in_env=0; next }
      if ($0 ~ /^[[:space:]]*environment:/) { in_env=1; env_indent=indent; next }
      if (in_env) {
        if ($0 ~ /^[[:space:]]*#/ || $0 ~ /^[[:space:]]*$/) next
        if (indent > env_indent) print
        else in_env=0
      }
    }
  ' "$1"
}

for entry in "${SERVICE_VARS[@]}"; do
  svc="${entry%%:*}"
  vars="${entry#*:}"
  echo "===== 服务 ${svc}（声明 ${vars//,/ }） ====="

  # 2.1 声明：.env.example 已登记
  for var in ${vars//,/ }; do
    if grep -qE "^${var}=" "$ENV_EXAMPLE"; then
      ok "${var} 已在 .env.example 登记"
    else
      fail "${var} 未在 .env.example 登记（声明清单要求）"
    fi

    # 2.2 透传：两个 compose 对应 service 的 environment 块
    for compose in "$COMPOSE_DEV" "$COMPOSE_PROD"; do
      name="$(basename "$compose")"
      if service_env_block "$compose" "$svc" | grep -qE "^[[:space:]]*${var}:"; then
        ok "${var} → ${name} ${svc} 已透传"
      else
        fail "${var} 未透传到 ${name} 的 ${svc} environment"
      fi
    done
  done

  # 2.3 反向：compose environment 块透传的 ${VAR} 必须已在 .env.example 登记
  for compose in "$COMPOSE_DEV" "$COMPOSE_PROD"; do
    name="$(basename "$compose")"
    passed="$(service_env_block "$compose" "$svc" | grep -oE '\$\{[A-Z_]+' | sed 's/^\${//' | sort -u)"
    for var in $passed; do
      if grep -qE "^${var}=" "$ENV_EXAMPLE"; then
        ok "${var} 反向校验通过（${name} ${svc} 透传 → 已登记）"
      else
        fail "${var} 在 ${name} ${svc} 透传但未在 .env.example 登记"
      fi
    done
  done

  # 2.4 生效：服务代码消费的变量 ⊆ 声明清单（防"代码消费但未登记/未透传"回归）
  consumed="$(grep -hoE 'os\.environ\.get\("[A-Z_]+"|os\.getenv\("[A-Z_]+"' "$ROOT/backend/$svc"/*.py 2>/dev/null \
    | grep -oE '"[A-Z_]+"' | tr -d '"' | sort -u || true)"
  for var in $consumed; do
    if grep -qE "(^|,)$var(,|$)" <<< ",$vars,"; then
      ok "${var} 代码消费 ⊆ 声明清单"
    else
      fail "${var} 被 ${svc} 代码消费但不在声明清单"
    fi
  done
  echo ""
done

if [ "$FAILURES" -eq 0 ]; then
  echo "全部通过 ✓（${#SERVICE_VARS[@]} 个服务声明清单与 .env.example / 两 compose 透传 / 代码消费一致）"
else
  echo "${FAILURES} 项失败 ✗——请按 FAIL 提示补齐声明/透传/消费"
fi
exit "$FAILURES"
