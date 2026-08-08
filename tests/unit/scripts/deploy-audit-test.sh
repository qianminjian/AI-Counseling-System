#!/usr/bin/env bash
# ============================================================
# DOC-078: deploy/scripts/deploy-audit.sh 行为测试（TDD）
# 验证：日志回归分析（R1-R6）、固定格式审计报告、主动修复（A1/A2）、
#       trap 链编排集成
# 用法：bash tests/unit/scripts/deploy-audit-test.sh
# ============================================================
set -euo pipefail

METRICS="$(cd "$(dirname "$0")/../../.." && pwd)/deploy/scripts/deploy-metrics.sh"
AUDIT="$(cd "$(dirname "$0")/../../.." && pwd)/deploy/scripts/deploy-audit.sh"
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' EXIT

PASS=0
FAIL=0
ok() { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

echo "== DOC-078 deploy-audit 测试 =="

# ---- T1 库存在且可 source（含依赖库） ----
[ -f "$AUDIT" ] && ok "库文件存在" || bad "库文件缺失: ${AUDIT}"
# shellcheck disable=SC1090
source "$METRICS" || { echo "metrics source 失败"; exit 1; }
# shellcheck disable=SC1090
source "$AUDIT" || { echo "audit source 失败"; exit 1; }
ok "库可 source（audit 依赖 metrics）"

# ---- 日志构造辅助 ----
# mklog <dir> <name> <result> <signal> <steps_csv> [<extra_log_line>] [<no_stats>]
# steps_csv 例: "build-images=5000,rsync=2000"
mklog() {
  local dir="$1" name="$2" result="$3" signal="$4" steps_csv="$5"
  local extra="${6:-}" no_stats="${7:-0}" s
  {
    echo "🔍 前置检查..."
    echo "🎯 待部署组件: backend"
    if [ -n "$extra" ]; then echo "$extra"; fi
    if [ "$no_stats" = "0" ]; then
      echo "# deploy-metrics v1"
      echo "deploy_result=${result}"
      echo "deploy_components=backend"
      echo "deploy_duration_ms=1000"
      if [ -n "$steps_csv" ]; then
        # 与 dm_stats_block 真实格式一致：step_<key>_ms=<dur>
        echo "$steps_csv" | tr ',' '\n' | while IFS= read -r s; do
          if [ -n "$s" ]; then echo "step_${s%%=*}_ms=${s##*=}"; fi
        done
      fi
      echo "signal=${signal}"
      echo "signal_details="
    fi
  } > "$dir/$name"
}

# ---- T2 无历史日志（仅本次）→ 结论 OK ----
D2="$TEST_ROOT/t2"; mkdir -p "$D2"
mklog "$D2" "deploy-20260808-120000.log" SUCCESS OK "smoke=1000,rsync=2000"
R2F="$D2/audit-20260808-120000.md"
OUT=$(dm_audit_run "$D2" "$D2/deploy-20260808-120000.log")
[ -f "$R2F" ] && ok "T2 审计报告生成" || bad "T2 报告缺失"
grep -q "结论     : ✅ OK" "$R2F" && ok "T2 无历史 → 结论 OK" || bad "T2 结论非 OK"
grep -q "部署样本 : 1 次" "$R2F" && ok "T2 样本计数 1" || bad "T2 样本计数异常"

# ---- T3 报告固定字段 ----
R3F="$D2/audit-20260808-120000.md"
for field in "审计时间" "日志窗口" "部署样本" "回归项" "结论" "问题清单" "修复动作"; do
  grep -q "$field" "$R3F" && ok "T3 报告含 [$field]" || bad "T3 报告缺 [$field]"
done

# ---- T4 R2 部署成功率 ----
D4="$TEST_ROOT/t4"; mkdir -p "$D4"
for n in 01 02 03 04 05 06 07; do mklog "$D4" "deploy-20260808-120${n}.log" SUCCESS OK "smoke=1000"; done
for n in 08 09 10; do mklog "$D4" "deploy-20260808-120${n}.log" FAILED CRITICAL "smoke=1000"; done
R4F="$D4/audit-20260808-121010.log"   # 占位：直接用 run 生成
OUT=$(dm_audit_run "$D4" "$D4/deploy-20260808-121010.log")
R4FILE=$(echo "$OUT" | grep "报告" | sed 's/.*: //')
[ -n "$R4FILE" ] || R4FILE="$D4/audit-20260808-121010.md"
grep -q "部署成功率" "$R4FILE" && ok "T4 报告含 R2 行" || bad "T4 缺 R2 行"
grep -q "\[WARN\] R2" "$R4FILE" && ok "T4 3/10 失败 → R2 WARN" || bad "T4 R2 未 WARN"
# 6/10 失败 → CRITICAL
D4B="$TEST_ROOT/t4b"; mkdir -p "$D4B"
for n in 01 02 03 04; do mklog "$D4B" "deploy-20260808-120${n}.log" SUCCESS OK "smoke=1000"; done
for n in 05 06 07 08 09 10; do mklog "$D4B" "deploy-20260808-120${n}.log" FAILED CRITICAL "smoke=1000"; done
OUT=$(dm_audit_run "$D4B" "$D4B/deploy-20260808-121010.log")
R4BFILE=$(echo "$OUT" | grep "报告" | sed 's/.*: //')
grep -q "\[CRITICAL\] R2" "${R4BFILE:-$D4B/audit-20260808-121010.md}" && ok "T4 6/10 失败 → R2 CRITICAL" || bad "T4 R2 未 CRITICAL"

# ---- T5 R3 失败模式聚类 ----
D5="$TEST_ROOT/t5"; mkdir -p "$D5"
for n in 01 02 03; do
  mklog "$D5" "deploy-20260808-130${n}.log" FAILED CRITICAL "smoke=1000" "guardian consent code=20003 required"
done
mklog "$D5" "deploy-20260808-1304.log" SUCCESS OK "smoke=1000"
OUT=$(dm_audit_run "$D5" "$D5/deploy-20260808-1304.log")
R5FILE=$(echo "$OUT" | grep "报告" | sed 's/.*: //')
grep -q "\[WARN\] R3" "${R5FILE:-$D5/audit-20260808-1304.md}" && ok "T5 3 次 code=20003 → R3 WARN" || bad "T5 R3 未 WARN"
grep -q "监护人" "${R5FILE:-$D5/audit-20260808-1304.md}" && ok "T5 指引含监护人" || bad "T5 指引缺失"

# ---- T6 R4 耗时趋势（连续 3 次上升） ----
D6="$TEST_ROOT/t6"; mkdir -p "$D6"
mklog "$D6" "deploy-20260808-1401.log" SUCCESS OK "build-images=3000"
mklog "$D6" "deploy-20260808-1402.log" SUCCESS OK "build-images=4000"
mklog "$D6" "deploy-20260808-1403.log" SUCCESS OK "build-images=5000"
mklog "$D6" "deploy-20260808-1404.log" SUCCESS OK "build-images=6000"
mklog "$D6" "deploy-20260808-1405.log" SUCCESS OK "build-images=7000"
OUT=$(dm_audit_run "$D6" "$D6/deploy-20260808-1405.log")
R6FILE=$(echo "$OUT" | grep "报告" | sed 's/.*: //')
grep -q "\[WARN\] R4" "${R6FILE:-$D6/audit-20260808-1405.md}" && ok "T6 连续 3 次上升 → R4 WARN" || bad "T6 R4 未 WARN"
grep -q "build-images" "${R6FILE:-$D6/audit-20260808-1405.md}" && ok "T6 趋势步骤名出现" || bad "T6 趋势步骤名缺失"

# ---- T7 R5 信号分布 ----
D7="$TEST_ROOT/t7"; mkdir -p "$D7"
for n in 01 02 03 04; do mklog "$D7" "deploy-20260808-150${n}.log" SUCCESS OK "smoke=1000"; done
for n in 05 06 07 08 09 10; do mklog "$D7" "deploy-20260808-150${n}.log" SUCCESS WARN "smoke=9999000"; done
OUT=$(dm_audit_run "$D7" "$D7/deploy-20260808-151010.log")
R7FILE=$(echo "$OUT" | grep "报告" | sed 's/.*: //')
grep -q "\[WARN\] R5" "${R7FILE:-$D7/audit-20260808-151010.md}" && ok "T7 60% 非 OK → R5 WARN" || bad "T7 R5 未 WARN"

# ---- T8 R6 日志完整性 ----
D8="$TEST_ROOT/t8"; mkdir -p "$D8"
for n in 01 02 03; do
  mklog "$D8" "deploy-20260808-160${n}.log" SUCCESS OK "smoke=1000" "📦 MindSafe 部署汇报" 1
done
mklog "$D8" "deploy-20260808-1604.log" SUCCESS OK "smoke=1000"
OUT=$(dm_audit_run "$D8" "$D8/deploy-20260808-1604.log")
R8FILE=$(echo "$OUT" | grep "报告" | sed 's/.*: //')
grep -q "\[WARN\] R6" "${R8FILE:-$D8/audit-20260808-1604.md}" && ok "T8 3 份缺统计段 → R6 WARN" || bad "T8 R6 未 WARN"

# ---- T9 R1 步骤耗时回归（复用 dm_judge，无历史 → 绝对阈值） ----
D9="$TEST_ROOT/t9"; mkdir -p "$D9"
mklog "$D9" "deploy-20260808-1701.log" SUCCESS OK "smoke=950000"   # smoke 绝对阈值 900s=900000ms
OUT=$(dm_audit_run "$D9" "$D9/deploy-20260808-1701.log")
R9FILE=$(echo "$OUT" | grep "报告" | sed 's/.*: //')
grep -q "\[WARN\] R1" "${R9FILE:-$D9/audit-20260808-1701.md}" && ok "T9 本次超阈值 → R1 WARN" || bad "T9 R1 未 WARN"
grep -q "smoke" "${R9FILE:-$D9/audit-20260808-1701.md}" && ok "T9 问题含 smoke 步骤" || bad "T9 smoke 步骤名缺失"

# ---- T10 A1 日志轮转 ----
D10="$TEST_ROOT/t10"; mkdir -p "$D10"
for n in $(seq -w 1 55); do
  printf 'dummy' > "$D10/deploy-20260808-18${n}.log"
done
for n in 01 02 03; do
  printf 'audit' > "$D10/audit-20260808-18${n}.md"
done
CLEANED=$(dm_audit_rotate "$D10" 50)
COUNT=$(ls "$D10"/deploy-*.log 2>/dev/null | wc -l | tr -d ' ')
[ "$COUNT" = "50" ] && ok "T10 轮转后 deploy 日志 50 份" || bad "T10 deploy 日志 ${COUNT} 份"
[ "$CLEANED" = "5" ] && ok "T10 清理 5 份" || bad "T10 清理 ${CLEANED} 份"
AUDCNT=$(ls "$D10"/audit-*.md 2>/dev/null | wc -l | tr -d ' ')
[ "$AUDCNT" = "3" ] && ok "T10 不动 audit 报告" || bad "T10 audit 被误删（${AUDCNT}）"

# ---- T11 终端摘要 ----
D11="$TEST_ROOT/t11"; mkdir -p "$D11"
mklog "$D11" "deploy-20260808-1901.log" SUCCESS OK "smoke=1000"
OUT=$(dm_audit_run "$D11" "$D11/deploy-20260808-1901.log")
echo "$OUT" | grep -q "结论" && ok "T11 摘要含结论" || bad "T11 摘要缺结论"
echo "$OUT" | grep -q "异常" && ok "T11 摘要含异常数" || bad "T11 摘要缺异常数"
echo "$OUT" | grep -q "修复" && ok "T11 摘要含修复动作" || bad "T11 摘要缺修复动作"
echo "$OUT" | grep -q "报告" && ok "T11 摘要含报告路径" || bad "T11 摘要缺报告路径"

# ---- T12 编排集成（模拟 deploy.sh trap 链） ----
D12="$TEST_ROOT/t12"; mkdir -p "$D12"
STATE="$D12/state"
LOG="$D12/deploy-20260808-2001.log"
dm_reset
DM_IN_FLOW=1
dm_start precheck
dm_end precheck
dm_start smoke
dm_end smoke
dm_finish_deploy 0 "backend" "$STATE" "$D12" "$LOG"
dm_audit_run "$D12" "$LOG"
[ -f "$D12/audit-20260808-2001.md" ] && ok "T12 trap 链产出审计报告" || bad "T12 审计报告缺失"
grep -q "结论" "$D12/audit-20260808-2001.md" && ok "T12 报告含结论" || bad "T12 报告缺结论"
grep -q "deploy_result=SUCCESS" "$LOG" && ok "T12 统计段先于审计写入" || bad "T12 统计段缺失"

echo ""
echo "deploy-audit 测试: ${PASS} 通过 / ${FAIL} 失败"
[ "$FAIL" -eq 0 ] || exit 1
