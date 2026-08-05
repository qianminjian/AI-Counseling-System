#!/usr/bin/env bash
# ============================================================
# OD-009/S5: deploy/scripts/prepare-funasr.sh 行为测试（TDD）
# 验证：版本比较已删除（恒真比较=死逻辑）；模型存在性校验与
#       fail-fast 保留（缺失/下载失败 → 非零退出）
# 用法：bash tests/unit/scripts/prepare-funasr-test.sh
# ============================================================
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")/../../.." && pwd)/deploy/scripts/prepare-funasr.sh"
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' EXIT

PASS=0
FAIL=0
ok() { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# fake docker：记录调用、退出码可控（避免测试真拉起容器）
BIN="$TEST_ROOT/bin"
mkdir -p "$BIN"
cat > "$BIN/docker" <<'EOF'
#!/bin/bash
echo "FAKE_DOCKER $*" >> "${FAKE_DOCKER_LOG:?}"
exit "${FAKE_DOCKER_EXIT:-0}"
EOF
chmod +x "$BIN/docker"

CACHE="$TEST_ROOT/cache"
LOG="$TEST_ROOT/docker.log"

echo "== OD-009/S5 prepare-funasr 测试 =="

# ---- 1. 脚本存在且可执行 ----
[ -x "$SCRIPT" ] && ok "脚本存在且可执行" || bad "脚本缺失或不可执行: $SCRIPT"

# ---- 2. 版本比较已删除（恒真 revision 比较 / JSON 解析段零残留）----
if grep -E "get_model_version|revision|EXPECTED_MODELS\[.*\]=\"master\"" "$SCRIPT" > /dev/null; then
    bad "版本比较残留：EXPECTED_MODELS 版本字段或 get_model_version 仍存在"
else
    ok "版本比较已删除（无 revision 字段/JSON 解析段）"
fi

# ---- 3. 存在性校验保留（模型目录缺失 → 触发下载）----
if grep -qE "模型目录缺失|! -d|missing.*model|MODEL_CACHE.*hub" "$SCRIPT"; then
    ok "模型存在性校验分支保留"
else
    bad "模型存在性校验分支缺失"
fi

# ---- 4. 空缓存（manifest 不存在 + 目录缺失）→ 触发下载，docker 被调用 ----
rm -rf "$CACHE"; : > "$LOG"
FAKE_DOCKER_LOG="$LOG" FUNASR_CACHE_DIR="$CACHE" PATH="$BIN:$PATH" \
    bash "$SCRIPT" > /dev/null 2>&1
if grep -q "FAKE_DOCKER" "$LOG"; then ok "缓存缺失 → 触发下载（docker 被调用）"; else bad "缓存缺失未触发下载"; fi

# ---- 5. 模型齐全 → 跳过下载（docker 不调用，退出 0）----
mkdir -p "$CACHE/modelscope/hub/iic/SenseVoiceSmall" \
         "$CACHE/modelscope/hub/iic/emotion2vec_plus_large" \
         "$CACHE/modelscope/hub/damo/fsmn-vad"
: > "$LOG"
if FAKE_DOCKER_LOG="$LOG" FUNASR_CACHE_DIR="$CACHE" PATH="$BIN:$PATH" \
    bash "$SCRIPT" > /dev/null 2>&1; then
    ok "模型齐全 → 退出 0"
else
    bad "模型齐全应退出 0"
fi
if grep -q "FAKE_DOCKER" "$LOG"; then bad "模型齐全仍触发下载"; else ok "模型齐全 → 跳过下载（docker 未调用）"; fi

# ---- 6. 下载失败（docker 非零）→ fail-fast 非零退出 ----
rm -rf "$CACHE"; : > "$LOG"
if FAKE_DOCKER_LOG="$LOG" FAKE_DOCKER_EXIT=1 FUNASR_CACHE_DIR="$CACHE" PATH="$BIN:$PATH" \
    bash "$SCRIPT" > /dev/null 2>&1; then
    bad "下载失败应非零退出（fail-fast）"
else
    ok "下载失败 → 非零退出（fail-fast）"
fi

# ---- 7. --force 强制下载（docker 被调用）----
mkdir -p "$CACHE/modelscope/hub/iic/SenseVoiceSmall" \
         "$CACHE/modelscope/hub/iic/emotion2vec_plus_large" \
         "$CACHE/modelscope/hub/damo/fsmn-vad"
: > "$LOG"
FAKE_DOCKER_LOG="$LOG" FUNASR_CACHE_DIR="$CACHE" PATH="$BIN:$PATH" \
    bash "$SCRIPT" --force > /dev/null 2>&1
if grep -q "FAKE_DOCKER" "$LOG"; then ok "--force 强制下载生效"; else bad "--force 未触发下载"; fi

echo ""
echo "结果: $PASS 通过, $FAIL 失败"
[[ "$FAIL" -eq 0 ]] || exit 1
