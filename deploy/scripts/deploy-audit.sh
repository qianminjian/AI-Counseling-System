#!/usr/bin/env bash
# ============================================================
# deploy-audit.sh —— 部署日志审计与回归分析库（DOC-078，doing/81）
# 供 deploy.sh source 使用（trap 链第二步）；依赖 deploy-metrics.sh（先 source）
#   source "$PROJECT_ROOT/deploy/scripts/deploy-metrics.sh"
#   source "$PROJECT_ROOT/deploy/scripts/deploy-audit.sh"
#
# 功能（规则表见 doing/81 §2.2，报告格式 §2.3）：
#   dm_audit_parse      解析日志窗口（最近 10 次）→ 记录行
#   dm_audit_rate       R2 部署成功率
#   dm_audit_cluster    R3 失败模式聚类（DM_DIAG_FEATURES 计数，与 dm_diagnose 同源）
#   dm_audit_trend      R4 耗时趋势（连续 3 次上升，变坏分析）
#   dm_audit_signaldist R5 信号分布（非 OK 占比）
#   dm_audit_integrity  R6 日志完整性（有汇报无统计段）
#   dm_audit_rotate     A1 主动修复：日志轮转（保留最近 N 份 deploy-*.log）
#   dm_audit_report     固定格式审计报告（全文落盘 + 终端摘要）
#   dm_audit_run        编排入口（deploy.sh trap 链第二步）
#
# 约束：bash 3.2（macOS 默认）兼容；变量一律 ${var} 包裹；
#       grep 无匹配用 if 包裹或 || true（set -eo pipefail 陷阱，DOC-077 教训）
# ============================================================

# ===== 日志窗口解析 =====
# dm_audit_parse <log_dir>：输出行 "basename|result|signal|steps(k=v,..)|has_stats|has_report"
# 窗口 = 文件名倒序（deploy-YYYYMMDD-HHMMSS.log 字典序即时间序）最近 10 份（含本次）
dm_audit_parse() {
  local log_dir="$1" f base result signal steps stats report
  local files
  files=$(ls "$log_dir"/deploy-*.log 2>/dev/null | sort -r | head -10 || true)
  for f in $files; do
    [ -f "$f" ] || continue
    base=$(basename "$f")
    result=$(grep '^deploy_result=' "$f" | head -1 | cut -d= -f2 || true)
    [ -z "$result" ] && result=NA
    signal=$(grep '^signal=' "$f" | head -1 | cut -d= -f2 || true)
    [ -z "$signal" ] && signal=NA
    # 真实统计段行格式 step_<key>_ms=<dur>；贪婪捕获到最后一个 _ms= 前，保留 = 分隔符（DOC-078 教训：直接删 _ms= 会丢 =）
    steps=$(grep '^step_.*_ms=' "$f" | sed 's/^step_\(.*\)_ms=/\1=/' | tr '\n' ',' | sed 's/,$//' || true)
    stats=0
    if grep -q '^deploy_result=' "$f"; then stats=1; fi
    report=0
    if grep -q '📦 MindSafe 部署汇报' "$f"; then report=1; fi
    echo "${base}|${result}|${signal}|${steps}|${stats}|${report}"
  done
}

# ===== R2 部署成功率：输出 "rate n_ok n_total"（rate 为整数百分比） =====
dm_audit_rate() {
  local records="$1" n_ok=0 n_total=0 rate
  local f result signal steps stats report
  while IFS='|' read -r f result signal steps stats report; do
    [ -z "$f" ] && continue
    n_total=$((n_total + 1))
    if [ "$result" = "SUCCESS" ]; then n_ok=$((n_ok + 1)); fi
  done <<< "$records"
  if [ "$n_total" -eq 0 ]; then echo "0 0 0"; return 0; fi
  rate=$((n_ok * 100 / n_total))
  echo "${rate} ${n_ok} ${n_total}"
}

# ===== R3 失败模式聚类：输出命中行 "pattern|count"（count ≥3） =====
dm_audit_cluster() {
  local log_dir="$1" records="$2"
  local text="" f result signal steps stats report pat guide cnt
  while IFS='|' read -r f result signal steps stats report; do
    [ -z "$f" ] && continue
    [ -f "$log_dir/$f" ] || continue
    text="${text}$(cat "$log_dir/$f" 2>/dev/null || true)\n"
  done <<< "$records"
  while IFS='|' read -r pat guide; do
    if [ -z "$pat" ]; then continue; fi
    # grep -c 无匹配返回 1 输出 0，|| true 兜底（set -e）
    cnt=$(printf '%b' "$text" | grep -c "$pat" || true)
    if [ "${cnt:-0}" -ge 3 ]; then echo "${pat}|${cnt}"; fi
  done <<< "$DM_DIAG_FEATURES"
}

# ===== R4 耗时趋势（变坏分析）：输出命中行 "key|v1|v2|v3"（v1 最新，连续上升） =====
dm_audit_trend() {
  local records="$1"
  local keys="" key f result signal steps stats report kv v
  # 窗口内步骤集合
  while IFS='|' read -r f result signal steps stats report; do
    [ -z "$steps" ] && continue
    for kv in $(echo "$steps" | tr ',' ' '); do
      key=${kv%%=*}
      case " ${keys} " in
        *" ${key} "*) ;;
        *) keys="${keys} ${key}" ;;
      esac
    done
  done <<< "$records"
  local v1="" v2="" v3="" n=0
  for key in $keys; do
    n=0; v1=""; v2=""; v3=""
    # records 最新在前：取该步骤最近 3 个值
    while IFS='|' read -r f result signal steps stats report; do
      v=$(echo "$steps" | tr ',' '\n' | grep -F "${key}=" | head -1 | cut -d= -f2 || true)
      if [ -n "$v" ]; then
        n=$((n + 1))
        if [ "$n" -eq 1 ]; then v1="$v"
        elif [ "$n" -eq 2 ]; then v2="$v"
        elif [ "$n" -eq 3 ]; then v3="$v"; break
        fi
      fi
    done <<< "$records"
    if [ -n "$v1" ] && [ -n "$v2" ] && [ -n "$v3" ] \
       && [ "$v1" -gt "$v2" ] && [ "$v2" -gt "$v3" ]; then
      echo "${key}|${v1}|${v2}|${v3}"
    fi
  done
}

# ===== R5 信号分布：输出 "pct n_non n_total" =====
dm_audit_signaldist() {
  local records="$1" n_non=0 n_total=0 pct
  local f result signal steps stats report
  while IFS='|' read -r f result signal steps stats report; do
    [ -z "$f" ] && continue
    n_total=$((n_total + 1))
    if [ "$signal" = "WARN" ] || [ "$signal" = "CRITICAL" ]; then n_non=$((n_non + 1)); fi
  done <<< "$records"
  if [ "$n_total" -eq 0 ]; then echo "0 0 0"; return 0; fi
  pct=$((n_non * 100 / n_total))
  echo "${pct} ${n_non} ${n_total}"
}

# ===== R6 日志完整性：输出 "n_broken n_total"（有汇报无统计段 = 出口链路坏） =====
dm_audit_integrity() {
  local records="$1" n_broken=0 n_total=0
  local f result signal steps stats report
  while IFS='|' read -r f result signal steps stats report; do
    [ -z "$f" ] && continue
    n_total=$((n_total + 1))
    if [ "$report" = "1" ] && [ "$stats" = "0" ]; then n_broken=$((n_broken + 1)); fi
  done <<< "$records"
  echo "${n_broken} ${n_total}"
}

# ===== A1 主动修复：日志轮转（保留最近 max 份 deploy-*.log，删除最旧） =====
# 仅运行时产物（logs/ 为 gitignore），不触碰仓库文件；输出清理份数
dm_audit_rotate() {
  local log_dir="$1" max="$2"
  local files n_extra
  files=$(ls "$log_dir"/deploy-*.log 2>/dev/null | sort || true)
  n_extra=$(echo "$files" | grep -c . || true)
  [ -z "$n_extra" ] && n_extra=0
  if [ "$n_extra" -le "$max" ]; then
    echo 0
    return 0
  fi
  # sort 升序 = 最旧在前，清理前 (n_extra - max) 份
  echo "$files" | head -n "$((n_extra - max))" | while IFS= read -r f; do
    rm -f "$f"
  done
  echo "$((n_extra - max))"
}

# ===== 步骤原因映射（与 dm_report 建议区同源，doing/81 R1/R4 用） =====
dm_audit_cause() {
  local key="$1"
  case "$key" in
    build-images) echo "服务器磁盘/网络负载；docker builder prune 可缓解（L3）" ;;
    rsync) echo "本机上行带宽不足；L2 降速 --bwlimit 可缓解" ;;
    smoke) echo "LLM/TTS 服务响应慢；检查外部服务健康" ;;
    restart) echo "voice 模型加载慢；检查服务器内存/模型缓存" ;;
    *) echo "检查服务器资源与网络" ;;
  esac
}

# ===== 固定格式审计报告 =====
# dm_audit_report <log_dir> <records> <deploy_log> <report_file> [<cleaned>]
# 全文落盘 report_file；stdout 输出终端摘要
dm_audit_report() {
  local log_dir="$1" records="$2" deploy_log="$3" report_file="$4" cleaned="${5:-0}"
  local n_ok=0 n_total=0 n_non=0 n_broken=0
  local rate=100 pct=0
  local f result signal steps stats report line key dur abs base judge_line lvl
  local problems="" level="OK" n_problems=0
  local r1_levels="" r1_line="PASS" r2_line="PASS" r3_line="PASS" r4_line="PASS" r5_line="PASS" r6_line="PASS"
  local cluster_hits="" trend_hits="" pat cnt guide v1 v2 v3 icon manual cause

  # R2/R5/R6 窗口统计
  while IFS='|' read -r f result signal steps stats report; do
    [ -z "$f" ] && continue
    n_total=$((n_total + 1))
    if [ "$result" = "SUCCESS" ]; then n_ok=$((n_ok + 1)); fi
    if [ "$signal" = "WARN" ] || [ "$signal" = "CRITICAL" ]; then n_non=$((n_non + 1)); fi
    if [ "$report" = "1" ] && [ "$stats" = "0" ]; then n_broken=$((n_broken + 1)); fi
  done <<< "$records"
  if [ "$n_total" -gt 0 ]; then
    rate=$((n_ok * 100 / n_total))
    pct=$((n_non * 100 / n_total))
  fi

  # R1 步骤耗时回归：本次步骤 vs 历史基线（排除本次日志）
  if [ -f "$deploy_log" ]; then
    while IFS= read -r line; do
      key=${line#step_}; key=${key%%_ms=*}
      dur=${line##*=}
      abs=$(dm_step_threshold "$key")
      [ -z "$abs" ] && abs=60
      base=$(dm_baseline "$key" "$log_dir" "$(basename "$deploy_log")")
      judge_line=$(dm_judge "$key" "$dur" "$abs" "$base")
      lvl=$(echo "$judge_line" | cut -d' ' -f1)
      if [ "$lvl" = "WARN" ] || [ "$lvl" = "CRITICAL" ]; then
        r1_levels="${r1_levels} ${key}=${lvl}"
      fi
    done < <(grep '^step_.*_ms=' "$deploy_log" || true)
  fi
  if [ -n "$r1_levels" ]; then
    r1_line="⚠️$(echo "$r1_levels" | tr ' ' ',' | sed 's/^,//')"
    for kv in ${r1_levels}; do
      key=${kv%%=*}; lvl=${kv##*=}
      cause=$(dm_audit_cause "$key")
      n_problems=$((n_problems + 1))
      problems="${problems}  [P${n_problems}][${lvl}] R1：${key} 本次耗时超阈值（${lvl}）\n  建议: ${cause}\n"
      if [ "$lvl" = "CRITICAL" ]; then level=CRITICAL; fi
      if [ "$lvl" = "WARN" ] && [ "$level" != "CRITICAL" ]; then level=WARN; fi
    done
  fi

  # R2 部署成功率
  if [ "$rate" -lt 60 ]; then
    n_problems=$((n_problems + 1))
    problems="${problems}  [P${n_problems}][CRITICAL] R2：部署成功率 ${rate}%（<60%）\n  建议: 查看最近失败日志，匹配失败模式知识库指引（code=20003/TS2741/路由 404 等）\n"
    level=CRITICAL
    r2_line="❌ ${rate}% <60%"
  elif [ "$rate" -lt 80 ]; then
    n_problems=$((n_problems + 1))
    problems="${problems}  [P${n_problems}][WARN] R2：部署成功率 ${rate}%（<80%）\n  建议: 关注失败部署日志中的失败特征，必要时修复后重试\n"
    if [ "$level" != "CRITICAL" ]; then level=WARN; fi
    r2_line="⚠️ ${rate}% <80%"
  fi

  # R3 失败模式聚类
  cluster_hits=$(dm_audit_cluster "$log_dir" "$records")
  if [ -n "$cluster_hits" ]; then
    while IFS='|' read -r pat cnt; do
      if [ -z "$pat" ]; then continue; fi
      guide=$(echo "$DM_DIAG_FEATURES" | grep -F "${pat}|" | head -1 | cut -d'|' -f2 || true)
      n_problems=$((n_problems + 1))
      problems="${problems}  [P${n_problems}][WARN] R3：失败模式 ${pat} 窗口内出现 ${cnt} 次（≥3，系统性风险）\n  建议: ${guide:-对照失败模式知识库处理}\n"
      if [ "$level" != "CRITICAL" ]; then level=WARN; fi
    done <<< "$cluster_hits"
    r3_line="⚠️ $(echo "$cluster_hits" | grep -c . || true) 特征高频"
  fi

  # R4 耗时趋势（变坏分析）
  trend_hits=$(dm_audit_trend "$records")
  if [ -n "$trend_hits" ]; then
    while IFS='|' read -r key v1 v2 v3; do
      if [ -z "$key" ]; then continue; fi
      cause=$(dm_audit_cause "$key")
      n_problems=$((n_problems + 1))
      problems="${problems}  [P${n_problems}][WARN] R4：${key} 耗时连续 3 次上升（${v1}→${v2}→${v3}ms），可能变坏\n  建议: ${cause}\n"
      if [ "$level" != "CRITICAL" ]; then level=WARN; fi
    done <<< "$trend_hits"
    r4_line="⚠️ $(echo "$trend_hits" | cut -d'|' -f1 | tr '\n' ',' | sed 's/,$//') 连续上升"
  fi

  # R5 信号分布
  if [ "$pct" -ge 50 ]; then
    n_problems=$((n_problems + 1))
    problems="${problems}  [P${n_problems}][WARN] R5：窗口内 ${pct}% 部署信号非 OK（≥50%）\n  建议: 结合 R1/R4 明细定位高频超阈值步骤\n"
    if [ "$level" != "CRITICAL" ]; then level=WARN; fi
    r5_line="⚠️ ${pct}% ≥50%"
  fi

  # R6 日志完整性
  if [ "$n_broken" -gt 2 ]; then
    n_problems=$((n_problems + 1))
    problems="${problems}  [P${n_problems}][WARN] R6：${n_broken} 份日志有部署汇报但缺统计段（>2，出口链路异常）\n  建议: 检查 deploy.sh trap 出口与磁盘空间\n"
    if [ "$level" != "CRITICAL" ]; then level=WARN; fi
    r6_line="⚠️ ${n_broken} 份缺统计段"
  fi

  if [ "$level" = "CRITICAL" ]; then icon="❌"; manual="存在 CRITICAL 问题，确认后人工处理（审计不自动回滚）"
  elif [ "$level" = "WARN" ]; then icon="⚠️"; manual="按问题清单建议项处理"
  else icon="✅"; manual="无"; fi

  # 报告全文落盘
  {
    echo "================================================"
    echo "🛡 MindSafe 部署审计报告（DOC-078 回归分析）"
    echo "================================================"
    echo "审计时间 : $(date '+%Y-%m-%d %H:%M:%S')"
    echo "日志窗口 : ${log_dir}/deploy-*.log（最近 10 次，含本次）"
    echo "部署样本 : ${n_total} 次（成功 ${n_ok} / 失败 $((n_total - n_ok))，成功率 ${rate}%）"
    echo "回归项   :"
    echo "  [R1] 步骤耗时回归  ${r1_line}"
    echo "  [R2] 部署成功率    ${rate}% ${r2_line}"
    echo "  [R3] 失败模式聚类  ${r3_line}"
    echo "  [R4] 耗时趋势      ${r4_line}"
    echo "  [R5] 信号分布      ${pct}% 非 OK ${r5_line}"
    echo "  [R6] 日志完整性    ${r6_line}"
    echo "结论     : ${icon} ${level}（${n_problems} 项异常）"
    echo "问题清单 :"
    if [ -n "$problems" ]; then
      printf '%b' "$problems"
    else
      echo "  （无）"
    fi
    echo "修复动作 :"
    echo "  ✅ 已执行: A1 日志轮转（上限 50 份，清理 ${cleaned} 份）"
    echo "  ➡ 待人工: ${manual}"
    echo "审计依据 : 规则表 doing/81 §2.2（R1-R6）；报告格式 §2.3"
    echo "================================================"
  } > "$report_file"

  # 终端摘要
  echo "==============================================="
  echo "🛡 MindSafe 部署审计（DOC-078）"
  echo "结论   : ${icon} ${level}（${n_problems} 项异常）"
  echo "样本   : ${n_total} 次（成功 ${n_ok} / 失败 $((n_total - n_ok))）"
  echo "修复   : ✅ A1 日志轮转（上限 50 份，清理 ${cleaned} 份）"
  echo "报告   : ${report_file}"
  echo "==============================================="
}

# ===== 编排入口（deploy.sh trap 链第二步，dm_finish_deploy 之后调用） =====
# dm_audit_run <log_dir> <deploy_log_file>：解析窗口 → A1 轮转 → 报告 + 摘要
dm_audit_run() {
  local log_dir="$1" deploy_log="$2"
  local records cleaned report_file
  records=$(dm_audit_parse "$log_dir")
  cleaned=$(dm_audit_rotate "$log_dir" 50)
  report_file="${log_dir}/audit-$(basename "$deploy_log" | sed 's/^deploy-//; s/\.log$//').md"
  dm_audit_report "$log_dir" "$records" "$deploy_log" "$report_file" "$cleaned"
}
