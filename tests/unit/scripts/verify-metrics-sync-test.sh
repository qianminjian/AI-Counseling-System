#!/usr/bin/env bash
# ============================================================
# DEV-P3-05（doing/96）：scripts/verify-metrics-sync.sh 行为测试
# 验证：py-common 共享单源存在（metrics_common/config_loader）+ 双服务无本地副本
#   A. 真实仓库全绿（单源当前成立，CI 上即回归护栏）
#   B. fixture 破坏性用例（单源缺失 → 退出非 0）
# 用法：bash tests/unit/scripts/verify-metrics-sync-test.sh
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SCRIPT="$ROOT/backend/scripts/verify-metrics-sync.sh"
BACKEND="$ROOT/backend"

PASS=0
FAIL=0
ok() { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

echo "== DEV-P3-05 verify-metrics-sync 测试 =="

# ---- A. 脚本存在 + 真实仓库全绿 ----
[ -f "$SCRIPT" ] && ok "脚本存在" || bad "脚本缺失: $SCRIPT"
[ -f "$BACKEND/py-common/metrics_common.py" ] && ok "py-common/metrics_common.py 单源存在" || bad "单源缺失: metrics_common.py"
[ -f "$BACKEND/py-common/config_loader.py" ] && ok "py-common/config_loader.py 单源存在" || bad "单源缺失: config_loader.py"

if bash "$SCRIPT" > /tmp/vms-real.log 2>&1; then
  ok "真实仓库单源契约全绿（退出 0）"
  grep -q "共享单源完整" /tmp/vms-real.log && ok "输出含完整结论" || bad "输出缺少完整结论"
else
  bad "真实仓库契约应退出 0（单源已漂移，先修复再接线）"
fi

# ---- B. fixture 破坏性用例：单源缺失 → 非零退出 ----
FIXTURE=$(mktemp -d)
trap 'rm -rf "$FIXTURE"' EXIT
# 构造最小 fixture：py-common 目录存在但缺 metrics_common/config_loader（脚本 BASE 固定为
# backend/ 相对路径无法直接指向 fixture——故用行替换生成指向 fixture 的脚本副本，验证核心判定）
mkdir -p "$FIXTURE/py-common"
SCRIPT_COPY="$FIXTURE/verify-metrics-sync.sh"
sed "s|^BASE=.*|BASE=\"$FIXTURE\"|" "$SCRIPT" > "$SCRIPT_COPY"
if bash "$SCRIPT_COPY" > /tmp/vms-fixture.log 2>&1; then
  bad "fixture（单源缺失）应退出非 0，实际退出 0"
else
  ok "fixture（单源缺失）正确拒绝（退出非 0）"
  grep -q "缺失" /tmp/vms-fixture.log && ok "错误信息含缺失提示" || bad "错误信息缺少缺失提示"
fi

echo ""
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
