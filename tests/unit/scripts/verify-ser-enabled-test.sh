#!/usr/bin/env bash
# tests/unit/scripts/verify-ser-enabled-test.sh —— DA-15 entrypoint 感知 SER_ENABLED（bash 单测，无 docker 依赖）
#
# 覆盖：
#   1. entrypoint.sh 读取 SER_ENABLED，默认 true（与 app.py 默认值对齐）
#   2. SER_ENABLED=false 时 REQUIRED_MODELS 剔除 emotion2vec（模型检查面与加载面一致）
#   3. 行为验证（fixture HOME 直接执行真实 entrypoint.sh）：
#      A. false + 仅 ASR 模型 → 部署不阻断（退出 0）
#      B. true + 仅 ASR 模型 → 缺 emotion2vec 拒绝启动（退出 1）
#      C. 未设置（默认 true）+ 仅 ASR 模型 → 同 B（默认行为一致）
#      D. true + 双模型齐 → 通过（退出 0）
#
# 运行：bash tests/unit/scripts/verify-ser-enabled-test.sh （退出码 0=全绿）

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
ENTRYPOINT="$ROOT/backend/voice-service/entrypoint.sh"
APP_PY="$ROOT/backend/voice-service/app.py"

FAILURES=0
ok()   { echo "ok   - $1"; }
fail() { echo "FAIL - $1"; FAILURES=$((FAILURES + 1)); }

check() { # check <描述> <期望值> <实际值>
    if [ "$2" = "$3" ]; then ok "$1"; else fail "$1 (期望 [$2] 实际 [$3])"; fi
}

# ===== fixture：模拟 modelscope 模型缓存目录 =====
F=$(mktemp -d)
trap 'rm -rf "$F"' EXIT
mkdir -p "$F/home/.cache/modelscope/hub/iic/SenseVoiceSmall"
touch "$F/home/.cache/modelscope/hub/iic/SenseVoiceSmall/placeholder"

run_entrypoint() { # run_entrypoint <SER_ENABLED 或 -> <ASR_ENGINE>
    local ser="${1:--}" engine="${2:-funasr}"
    (
        export HOME="$F/home" ASR_ENGINE="$engine"
        if [ "$ser" = "-" ]; then
            unset SER_ENABLED
        else
            export SER_ENABLED="$ser"
        fi
        bash "$ENTRYPOINT" true
    ) 2>&1
}

# ===== 1. 静态面（防回潮） =====
[ -f "$ENTRYPOINT" ] && ok "entrypoint.sh 存在" || fail "entrypoint.sh 缺失"

grep -q 'SER_ENABLED' "$ENTRYPOINT" && ok "entrypoint.sh 读取 SER_ENABLED" \
    || fail "entrypoint.sh 未读取 SER_ENABLED"

grep -q 'SER_ENABLED:-true' "$ENTRYPOINT" && ok "entrypoint SER_ENABLED 默认 true" \
    || fail "entrypoint SER_ENABLED 默认值缺失/非 true"

grep -q "tr '\[\:upper\:\]'" "$ENTRYPOINT" && ok "SER_ENABLED 大小写归一化（与 app.py .lower() 语义一致）" \
    || fail "SER_ENABLED 未大小写归一化（TRUE/True 时检查面与加载面劈叉）"

grep -q 'SER_ENABLED", "true"' "$APP_PY" && ok "app.py SER_ENABLED 默认 true（与 entrypoint 一致）" \
    || fail "app.py SER_ENABLED 默认值漂移"

grep -q 'REQUIRED_MODELS+=' "$ENTRYPOINT" && ok "emotion2vec 条件追加逻辑存在" \
    || fail "REQUIRED_MODELS 缺少条件追加（剔除逻辑缺失）"

grep -q 'REQUIRED_MODELS=("iic/SenseVoiceSmall")' "$ENTRYPOINT" && ok "ASR 模型始终在必需列表" \
    || fail "REQUIRED_MODELS 基础列表异常"

# ===== 2. 行为面（直接执行真实 entrypoint.sh） =====

# A. SER_ENABLED=false + 仅 ASR 模型 → 部署不阻断
OUT=$(run_entrypoint false)
if [ "$?" -eq 0 ]; then ok "A: SER_ENABLED=false 无 emotion2vec → 退出 0（不阻断部署）"; else fail "A: 退出码非 0"; fi
echo "$OUT" | grep -q 'emotion2vec' && fail "A: 输出仍检查 emotion2vec" || ok "A: 输出不含 emotion2vec"

# B. SER_ENABLED=true + 仅 ASR 模型 → 缺模型拒绝启动
OUT=$(run_entrypoint true || true)
if echo "$OUT" | grep -q 'emotion2vec' && echo "$OUT" | grep -q '缺失'; then
    ok "B: SER_ENABLED=true 缺 emotion2vec → 明确报错"
else
    fail "B: 未报告 emotion2vec 缺失"
fi

# C. 未设置（默认 true）+ 仅 ASR 模型 → 同 B（默认行为一致）
OUT=$(run_entrypoint - || true)
if echo "$OUT" | grep -q 'emotion2vec'; then
    ok "C: 默认（未设 SER_ENABLED）仍要求 emotion2vec（默认 true）"
else
    fail "C: 默认行为与 SER_ENABLED=true 不一致"
fi

# E. SER_ENABLED=TRUE（大写）→ 与 true 同判定（大小写归一化，与 app.py .lower() 一致）
OUT=$(run_entrypoint TRUE || true)
if echo "$OUT" | grep -q 'emotion2vec' && echo "$OUT" | grep -q '缺失'; then
    ok "E: SER_ENABLED=TRUE（大写）→ 同 true 判定（缺失 emotion2vec 报错）"
else
    fail "E: 大写 TRUE 未与 true 同判定"
fi

# D. SER_ENABLED=true + 双模型齐 → 通过
mkdir -p "$F/home/.cache/modelscope/hub/iic/emotion2vec_plus_large"
touch "$F/home/.cache/modelscope/hub/iic/emotion2vec_plus_large/placeholder"
if HOME="$F/home" ASR_ENGINE=funasr SER_ENABLED=true bash "$ENTRYPOINT" true >/dev/null 2>&1; then
    ok "D: 双模型齐 → 退出 0"
else
    fail "D: 双模型齐仍失败"
fi

# ===== 3. 语法检查 =====
bash -n "$ENTRYPOINT" && ok "bash -n 通过: entrypoint.sh" || fail "bash -n 失败: entrypoint.sh"

echo ""
if [ "$FAILURES" -eq 0 ]; then
    echo "全部通过 ✓"
else
    echo "${FAILURES} 项失败 ✗"
fi
exit "$FAILURES"
