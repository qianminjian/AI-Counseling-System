#!/usr/bin/env bash
# ============================================================
# E2: scripts/db-rollback-drill.sh 行为测试（TDD）
# 验证：rollback 配对校验、倒序回滚计划、dry-run 默认安全
# 用法：bash tests/unit/scripts/db-rollback-drill-test.sh
# ============================================================
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")/../../.." && pwd)/scripts/db-rollback-drill.sh"
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' EXIT

PASS=0
FAIL=0
ok() { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# ---- fixture 迁移目录：V1~V3，其中 V1/V3 有 rollback，V2 缺失 ----
MIG="$TEST_ROOT/migrations"
mkdir -p "$MIG"
echo "-- up1" > "$MIG/V1__init.sql"
echo "-- down1" > "$MIG/V1__init.rollback.sql"
echo "-- up2" > "$MIG/V2__add_field.sql"
echo "-- up3" > "$MIG/V3__new_table.sql"
echo "-- down3" > "$MIG/V3__new_table.rollback.sql"

echo "== E2 db-rollback-drill 测试 =="

# ---- 1. 脚本存在且可执行 ----
[ -x "$SCRIPT" ] && ok "脚本存在且可执行" || bad "脚本缺失或不可执行: $SCRIPT"

# ---- 2. 无参数 → 非 0 退出 + 输出用法 ----
if "$SCRIPT" > /dev/null 2>&1; then bad "无参数应报错"; else ok "无参数退出非 0"; fi
USAGE_OUT=$("$SCRIPT" 2>&1 || true)
echo "$USAGE_OUT" | grep -q "用法" && ok "输出用法说明" || bad "未输出用法说明"

# ---- 3. check：缺失 rollback 默认警告但退出 0 ----
OUT=$("$SCRIPT" check --dir "$MIG" 2>&1)
echo "$OUT" | grep -q "V2__add_field.sql" && ok "check 指出 V2 缺失 rollback" || bad "check 未指出 V2 缺失"
echo "$OUT" | grep -qi "警告" && ok "check 输出警告" || bad "check 未输出警告"

# ---- 4. check --strict：缺失 rollback → 非 0 ----
if "$SCRIPT" check --strict --dir "$MIG" > /dev/null 2>&1; then
  bad "--strict 下缺失 rollback 应退出非 0"
else
  ok "--strict 缺失 rollback 退出非 0"
fi

# ---- 5. check：全配对目录 → 0（含 --strict）----
MIG2="$TEST_ROOT/migrations_full"
mkdir -p "$MIG2"
echo "-- up1" > "$MIG2/V1__init.sql"
echo "-- down1" > "$MIG2/V1__init.rollback.sql"
if "$SCRIPT" check --strict --dir "$MIG2" > /dev/null 2>&1; then ok "全配对 check --strict 退出 0"; else bad "全配对 check --strict 应退出 0"; fi

# ---- 6. drill --target：输出倒序回滚计划（含路径、不含 target 自身）----
PLAN=$("$SCRIPT" drill --dir "$MIG" --target V2)
echo "$PLAN" | grep -q "V3__new_table.rollback.sql" && ok "计划包含 V3 rollback" || bad "计划缺 V3 rollback"
echo "$PLAN" | grep -q "V2__add_field.rollback.sql" && bad "计划不应包含 target 自身 rollback" || ok "计划不含 target 自身"
echo "$PLAN" | grep -q "V1__init.rollback.sql" && bad "计划不应包含 target 之前版本" || ok "计划不含 target 之前版本"

# ---- 7. drill 无 --target：从最新回滚全部 ----
PLAN_ALL=$("$SCRIPT" drill --dir "$MIG")
echo "$PLAN_ALL" | grep -q "V3__new_table.rollback.sql" && ok "全量计划含 V3" || bad "全量计划缺 V3"
echo "$PLAN_ALL" | grep -q "V1__init.rollback.sql" && ok "全量计划含 V1" || bad "全量计划缺 V1"

# ---- 8. 默认 dry-run：输出为计划文本而非执行（无 psql 依赖）----
OUT_DRY=$("$SCRIPT" drill --dir "$MIG" 2>&1)
echo "$OUT_DRY" | grep -qi "回滚计划" && ok "dry-run 输出计划标题" || bad "dry-run 未输出计划标题"

# ---- 9. 非法命令 → 非 0 ----
if "$SCRIPT" foo --dir "$MIG" > /dev/null 2>&1; then bad "非法命令应退出非 0"; else ok "非法命令退出非 0"; fi

echo ""
echo "E2 测试结果：$PASS 通过 / $FAIL 失败"
[ "$FAIL" -eq 0 ]
