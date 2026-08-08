#!/usr/bin/env bash
# ============================================================
# DOC-077: deploy/scripts/deploy-metrics.sh 行为测试（TDD）
# 验证：步骤计时、基线构建、阈值判定、信号汇总、汇报格式、
#       条形图、失败模式知识库、日志结构化段
# 用法：bash tests/unit/scripts/deploy-metrics-test.sh
# ============================================================
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")/../../.." && pwd)/deploy/scripts/deploy-metrics.sh"
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' EXIT

PASS=0
FAIL=0
ok() { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

echo "== DOC-077 deploy-metrics 测试 =="

# ---- 0. 库存在且可 source ----
[ -f "$SCRIPT" ] && ok "库文件存在" || bad "库文件缺失: $SCRIPT"
# shellcheck disable=SC1090
source "$SCRIPT" || { echo "source 失败"; exit 1; }
ok "库可 source"

# ---- 1. T1 计时 ----
dm_reset
dm_start demo_step
sleep 1.2
dm_end demo_step
DUR=$(dm_get_ms demo_step)
if [ "${DUR:-0}" -ge 1000 ] && [ "$DUR" -lt 3000 ]; then
  ok "计时正确（${DUR}ms ≥ 1000ms）"
else
  bad "计时异常（${DUR}ms）"
fi

# ---- 2. T2 未 start 直接 end ----
dm_reset
if dm_end ghost_step 2>/dev/null; then
  bad "未 start 直接 end 未报错"
else
  ok "未 start 直接 end 报错"
fi

# ---- 3. T3 无基线判定 ----
dm_reset
J=$(dm_judge ok_step 5000 10 "")          # 5s > 10s 阈值 → OK
echo "$J" | grep -q "OK" && ok "无基线：阈值内 → OK" || bad "无基线：阈值内判定错误（${J}）"
J=$(dm_judge warn_step 15000 10 "")       # 15s > 10s → WARN
echo "$J" | grep -q "WARN" && ok "无基线：超阈值 → WARN" || bad "无基线：超阈值判定错误（${J}）"
J=$(dm_judge crit_step 25000 10 "")       # 25s > 2×10s → CRITICAL
echo "$J" | grep -q "CRITICAL" && ok "无基线：超 2× 阈值 → CRITICAL" || bad "无基线：超 2× 判定错误（${J}）"

# ---- 4. T4 有基线判定 ----
# 构造基线：5 次历史，p90 落在 12000ms 附近（10000,11000,12000,13000,14000 → p90=13600）
BASELINE="12000 13600 14000"
J=$(dm_judge base_ok 10000 10 "$BASELINE")        # 10s < max(p90×1.5, abs) → OK
echo "$J" | grep -q "OK" && ok "有基线：基线内 → OK" || bad "有基线：基线内判定错误（${J}）"
J=$(dm_judge base_warn 22000 10 "$BASELINE")      # 22s > p90×1.5=20.4s → WARN
echo "$J" | grep -q "WARN" && ok "有基线：超 p90×1.5 → WARN" || bad "有基线：WARN 判定错误（${J}）"
J=$(dm_judge base_crit 30000 10 "$BASELINE")      # 30s > p90×2=27.2s → CRITICAL
echo "$J" | grep -q "CRITICAL" && ok "有基线：超 p90×2 → CRITICAL" || bad "有基线：CRITICAL 判定错误（${J}）"

# ---- 5. T5 基线样本 <3 用 max 替代 p90 ----
J=$(dm_judge few 3000 10 "1000 2500")
echo "$J" | grep -q "OK" && ok "样本<3：max 替代 p90 正常判定" || bad "样本<3 判定错误（${J}）"

# ---- 6. T6 汇报格式 ----
LOG_DIR="$TEST_ROOT/logs"
REPORT=$(dm_report "backend student" "SUCCESS" "754000" "$LOG_DIR" "")
for key in "部署汇报" "组件" "结果" "总耗时" "信号" "日志"; do
  echo "$REPORT" | grep -q "$key" && ok "汇报含 [$key]" || bad "汇报缺 [$key]"
done
echo "$REPORT" | grep -q "SUCCESS" && ok "汇报含结果 SUCCESS" || bad "汇报结果缺失"

# ---- 7. T7 条形图 ----
BAR=$(dm_bar 754000 754000)
[ "${#BAR}" -ge 18 ] && ok "条形图满格（${#BAR} 格）" || bad "条形图满格异常（${#BAR}）"
BAR2=$(dm_bar 1000 754000)
[ "${#BAR2}" -ge 1 ] && ok "条形图最小 1 格" || bad "条形图最小格异常（${#BAR2}）"

# ---- 8. T8 失败模式知识库 ----
DIAG=$(dm_diagnose "$TEST_ROOT/fail-consent.log" "guardian consent code=20003 required")
echo "$DIAG" | grep -q "监护人同意" && ok "20003 特征 → 监护人同意指引" || bad "20003 指引缺失（${DIAG}）"
DIAG2=$(dm_diagnose "$TEST_ROOT/fail-ts.log" "error TS2741 props")
echo "$DIAG2" | grep -q "props" && ok "TS2741 特征 → props 指引" || bad "TS2741 指引缺失（${DIAG2}）"
DIAG3=$(dm_diagnose "$TEST_ROOT/ok.log" "all good")
[ -z "$DIAG3" ] && ok "无失败特征 → 无指引" || bad "无失败特征误报指引（${DIAG3}）"

# ---- 9. T9 日志结构化段 ----
STATS=$(dm_stats_block "backend" "SUCCESS" "754000" "OK" "warn_step=WARN")
for key in "deploy_result=SUCCESS" "deploy_components=backend" "deploy_duration_ms=754000" "signal=OK"; do
  echo "$STATS" | grep -q "$key" && ok "统计段含 [$key]" || bad "统计段缺 [$key]"
done

# ---- 10. T10 失败路径汇报 ----
REPORT_FAIL=$(dm_report "backend" "FAILED" "754000" "$LOG_DIR" "build-images")
echo "$REPORT_FAIL" | grep -q "FAILED" && ok "失败汇报含 FAILED" || bad "失败汇报缺 FAILED"
echo "$REPORT_FAIL" | grep -q "build-images" && ok "失败汇报含失败步骤" || bad "失败汇报缺失败步骤"

# ---- 11. T11 编排成功路径（dm_finish_deploy）----
DM_IN_FLOW=1
STATE_FILE="$TEST_ROOT/.deploy-state"
LOG_DIR="$TEST_ROOT/logs/deploy"
LOG_FILE="$LOG_DIR/deploy-20260808-120000.log"
mkdir -p "$LOG_DIR"
dm_reset
for k in precheck detect compile-backend build-student rsync build-images restart smoke nginx-check; do
  dm_start "$k"; dm_end "$k"
done
dm_finish_deploy 0 "backend student" "$STATE_FILE" "$LOG_DIR" "$LOG_FILE"
grep -q '^deploy_result=SUCCESS$' "$LOG_FILE" && ok "编排：统计段写入日志" || bad "编排：统计段缺失"
grep -q '^LAST_DEPLOY_SIGNAL=OK$' "$STATE_FILE" && ok "编排：快照 signal 落盘" || bad "编排：快照 signal 缺失"
BASE=$(dm_baseline compile-backend "$LOG_DIR")
[ -n "$BASE" ] && ok "编排：基线可解析（${BASE}ms）" || bad "编排：基线解析失败"
echo "$BASE" | grep -qE '^[0-9]+ [0-9]+ [0-9]+$' && ok "编排：基线三统计量" || bad "编排：基线格式异常"

# ---- 12. T12 编排失败路径（未完成步骤 → fail_step + CRITICAL）----
STATE_FILE2="$TEST_ROOT/.deploy-state-fail"
dm_reset
for k in precheck detect compile-backend build-student rsync; do
  dm_start "$k"; dm_end "$k"
done
dm_start build-images   # 不 end——模拟失败中断
REPORT_FAIL2=$(dm_finish_deploy 1 "backend" "$STATE_FILE2" "$LOG_DIR" "$LOG_FILE")
echo "$REPORT_FAIL2" | grep -q "FAILED" && ok "编排：失败汇报含 FAILED" || bad "编排：失败汇报缺 FAILED"
echo "$REPORT_FAIL2" | grep -q "build-images" && ok "编排：fail_step 推导为 build-images" || bad "编排：fail_step 推导错误"
echo "$REPORT_FAIL2" | grep -q "CRITICAL" && ok "编排：失败信号 CRITICAL" || bad "编排：失败信号错误"
grep -q '^deploy_result=FAILED$' "$LOG_FILE" && ok "编排：失败统计段落盘" || bad "编排：失败统计段缺失"
[ -f "$STATE_FILE2" ] && bad "编排：失败不应写快照" || ok "编排：失败不写快照"

echo ""
echo "deploy-metrics 测试: $PASS 通过 / $FAIL 失败"
[ "$FAIL" -eq 0 ]
