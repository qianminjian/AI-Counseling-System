#!/usr/bin/env bash
# ============================================================
# deploy-metrics.sh —— 部署计时与监控模型库（DOC-077，doing/80）
# 供 deploy.sh source 使用；独立库保证可测试性（tests/unit/scripts/deploy-metrics-test.sh）
#
# 功能：
#   dm_start/dm_end/dm_get_ms/dm_reset  步骤计时（毫秒，python3 取时）
#   dm_baseline                         历史基线（logs/deploy/deploy-*.log 最近 10 次）
#   dm_judge                            阈值判定（绝对 + 相对 p90）
#   dm_signal                           信号汇总（OK/WARN/CRITICAL）
#   dm_bar                              条形图（▇ 按总耗时比例，最小 1 格，满格 20）
#   dm_diagnose                         失败模式知识库（特征 → 修复指引）
#   dm_stats_block                      结构化统计段（日志解析数据源）
#   dm_report                           固定格式部署汇报
#
# 约束：bash 3.2（macOS 默认）兼容；变量一律 ${var} 包裹
#       （2026-08-07 全角字符丢字节教训，deploy.sh 注释已固化）
# ============================================================

# ===== 步骤元数据（key|中文名|绝对阈值秒） =====
# 绝对阈值依据见 doing/80 §2.1；调整阈值需同步设计文档
DM_STEP_META="
precheck|前置检查|60
detect|变更检测|10
compile-backend|后端预检编译|300
prepare-models|模型投放|600
build-student|学生端构建|240
build-teacher|教师端构建|240
build-parent|家长端构建|240
rsync|源码同步|600
build-images|镜像构建|1500
restart|服务重启|300
smoke|发布后置冒烟|900
nginx-check|nginx 路径校验|60
"

# 步骤 key → 中文名 / 绝对阈值秒
dm_step_name() { echo "$DM_STEP_META" | awk -F'|' -v k="$1" '$1==k {print $2}'; }
dm_step_threshold() { echo "$DM_STEP_META" | awk -F'|' -v k="$1" '$1==k {print $3}'; }

# ===== 计时状态（全局） =====
DM_START_MS=""   # "key=ms key=ms"
DM_KEYS=""       # 已完成步骤 key 列表（空格分隔）
DM_MS=""         # 与 DM_KEYS 平行："key=ms key=ms"
DM_LEVELS=""     # 判定记录："key=LEVEL key=LEVEL"

dm_now_ms() {
  python3 -c 'import time; print(int(time.time()*1000))'
}

dm_reset() {
  DM_START_MS=""
  DM_KEYS=""
  DM_MS=""
  DM_LEVELS=""
}

dm_start() {
  local key="$1"
  local now
  now=$(dm_now_ms)
  DM_START_MS="${DM_START_MS} ${key}=${now}"
}

dm_end() {
  local key="$1"
  local now dur entry
  now=$(dm_now_ms)
  entry=$(echo "$DM_START_MS" | tr ' ' '\n' | grep -F "${key}=" | head -1 || true)
  if [ -z "$entry" ]; then
    echo "❌ dm_end: 步骤 [${key}] 未 start" >&2
    return 1
  fi
  dur=$((now - ${entry#*=}))
  DM_KEYS="${DM_KEYS} ${key}"
  DM_MS="${DM_MS} ${key}=${dur}"
}

dm_get_ms() {
  local key="$1"
  echo "$DM_MS" | tr ' ' '\n' | grep -F "${key}=" | head -1 | cut -d= -f2
}

# ===== 基线：从部署日志提取步骤历史（最近 10 次） =====
# 输出 "mean p90 max"（空格分隔）；无历史输出空串
# 第三参 exclude（文件 basename）可选：排除指定日志（审计 R1 排除本次部署）
dm_baseline() {
  local key="$1" log_dir="$2" exclude="${3:-}"
  local vals="" f v
  for f in "$log_dir"/deploy-*.log; do
    [ -f "$f" ] || continue
    if [ -n "$exclude" ] && [ "$(basename "$f")" = "$exclude" ]; then continue; fi
    # pipefail 下 grep 无匹配返回 1 会传导到赋值语句触发 set -e，需 || true（部署首跑日志无统计段必无匹配）
    v=$(grep "step_${key}_ms=" "$f" 2>/dev/null \
      | sed 's/.*step_'${key}'_ms=\([0-9]*\).*/\1/' \
      | grep -E '^[0-9]+$' | head -1 || true)
    if [ -n "$v" ]; then vals="${vals}${v}\n"; fi
  done
  if [ -z "$vals" ]; then
    echo ""
    return 0
  fi
  printf '%b' "$vals" | awk '{s+=$1; n++; a[n]=$1} END {
    mean = (n>0) ? int(s/n) : 0;
    # 排序取 p90；样本 <3 用 max 替代
    for (i=1; i<=n; i++) for (j=i+1; j<=n; j++) if (a[i] > a[j]) {t=a[i]; a[i]=a[j]; a[j]=t}
    max = a[n];
    idx = int(n * 0.9); if (idx < 1) idx = 1;
    p90 = (n >= 3) ? a[idx] : max;
    printf "%d %d %d\n", mean, p90, max
  }'
}

# ===== 阈值判定 =====
# dm_judge <key> <dur_ms> <abs_threshold_s> <baseline_str("mean p90 max"|"")>
# 输出 "LEVEL dur mean p90 max"；并记录判定
dm_judge() {
  local key="$1" dur_ms="$2" abs_s="$3" base="$4"
  local abs_ms warn_ms crit_ms level
  abs_ms=$((abs_s * 1000))
  if [ -n "$base" ]; then
    # set -u 下 read 字段不足会留下未绑定变量，先给默认值
    local mean=0 p90=0 max=0
    read -r mean p90 max <<< "$base"
    if [ -z "$mean" ]; then mean=0; fi
    if [ -z "$p90" ]; then p90=0; fi
    if [ -z "$max" ]; then max=0; fi
    # 相对阈值：p90×1.5 / p90×2，与绝对阈值取大
    warn_ms=$((p90 * 15 / 10))
    if [ "$warn_ms" -lt "$abs_ms" ]; then warn_ms=$abs_ms; fi
    crit_ms=$((p90 * 2))
    if [ "$crit_ms" -lt "$((abs_ms * 2))" ]; then crit_ms=$((abs_ms * 2)); fi
  else
    warn_ms=$abs_ms
    crit_ms=$((abs_ms * 2))
  fi
  if [ "$dur_ms" -gt "$crit_ms" ]; then
    level=CRITICAL
  elif [ "$dur_ms" -gt "$warn_ms" ]; then
    level=WARN
  else
    level=OK
  fi
  DM_LEVELS="${DM_LEVELS} ${key}=${level}"
  echo "$level $dur_ms ${mean:-0} ${p90:-0} ${max:-0}"
}

# ===== 信号汇总（取最高级别） =====
# 注意：全部用 if 包裹 grep——`grep && echo` 在 set -e 下无匹配时列表整体返回 1 会误触发退出
dm_signal() {
  if echo "$DM_LEVELS" | tr ' ' '\n' | grep -qF '=CRITICAL'; then
    echo "CRITICAL"
    return 0
  fi
  if echo "$DM_LEVELS" | tr ' ' '\n' | grep -qF '=WARN'; then
    echo "WARN"
    return 0
  fi
  echo "OK"
}

# ===== 条形图（▇ 比例格，最少 1 格，满格 20） =====
dm_bar() {
  local dur_ms="$1" total_ms="$2" n=0 i bar=""
  if [ "$total_ms" -gt 0 ]; then
    n=$((dur_ms * 20 / total_ms))
    if [ "$n" -lt 1 ]; then n=1; fi
    if [ "$n" -gt 20 ]; then n=20; fi
  else
    n=1
  fi
  for i in $(seq 1 "$n"); do bar="${bar}▇"; done
  echo "$bar"
}

# ===== 失败模式知识库特征表（pattern|指引） =====
# dm_diagnose 与 dm_audit_cluster 共用（单一事实源，DOC-078）；新增特征需同步两处消费
DM_DIAG_FEATURES="
code=20003|监护人同意门禁（code=20003）：生产 trial-auto-grant=false 需 SMS 闭环，冒烟注册请用 age≥14 或补确认流程
TS2741|TypeScript TS2741：组件必填 props 缺参，检查报错组件调用处
NoResourceFoundException|NoResourceFoundException：API 路由不存在，核对脚本路径与真实路由
tts/personas|/tts/personas 403：SecurityConfig 白名单遗漏 permitAll
URI is not absolute|企微告警 URI is not absolute：WeCom webhook 环境变量缺失或格式错误
HttpMessageNotReadableException|HttpMessageNotReadableException：非法 JSON 未映射 400，检查全局异常处理
Additional property .* is not allowed|compose 配置结构错误（属性误插段落，如 REDIS_* 误入 build 段）：本地 docker compose config --quiet 预检可提前拦截，检查 yml 缩进/段落归属（DEPLOY-OPT-1）
Permission denied (13)|rsync 权限失败：服务器目标目录存在 root 遗留文件（如 dist/audio-test），用 docker run --rm -v 挂载 等效 root 清理后重试
No default constructor found|Spring 多构造器未标注：Bean 实例化失败，给主构造器加 @Autowired（多构造器需显式标注）
"

# ===== 失败模式知识库（特征 → 修复指引） =====
# dm_diagnose <logfile> [<text>]：text 为空时读 logfile；输出匹配到的指引
dm_diagnose() {
  local logfile="$1" text="${2:-}"
  if [ -z "$text" ] && [ -f "$logfile" ]; then
    text=$(cat "$logfile" 2>/dev/null || true)
  fi
  local out="" pat guide
  while IFS='|' read -r pat guide; do
    if [ -z "$pat" ]; then continue; fi
    # if 包裹：grep 无匹配返回 1，`&&` 写法在 set -e 下会误触发退出
    if echo "$text" | grep -q "$pat"; then out="${out}  • ${guide}\n"; fi
  done <<< "$DM_DIAG_FEATURES"
  printf '%b' "$out"
}

# ===== 结构化统计段（追加日志，供基线解析） =====
dm_stats_block() {
  local components="$1" result="$2" duration_ms="$3" signal="$4" details="$5"
  local key dur
  echo "# deploy-metrics v1"
  echo "deploy_result=${result}"
  echo "deploy_components=${components}"
  echo "deploy_duration_ms=${duration_ms}"
  for key in $DM_KEYS; do
    dur=$(dm_get_ms "$key")
    echo "step_${key}_ms=${dur}"
  done
  echo "signal=${signal}"
  echo "signal_details=${details}"
}

# ===== 固定格式汇报 =====
# dm_report <components> <result> <duration_ms> <log_dir> <fail_step> [<log_file>]
dm_report() {
  local components="$1" result="$2" duration_ms="$3" log_dir="$4" fail_step="$5" log_file="${6:-}"
  local duration_s total_s signal details key dur name base level bar diag
  local suggest="" keys="" names=""
  local dur_sec=$((duration_ms / 1000))
  local dur_min=$((dur_sec / 60)) dur_rem=$((dur_sec % 60))
  [ "$dur_min" -gt 0 ] && duration_s="${dur_min}m ${dur_rem}s" || duration_s="${dur_rem}s"
  total_s=$((duration_ms / 1000)); [ "$total_s" -lt 1 ] && total_s=1

  # 步骤明细 + 判定（未执行步骤不出现）
  keys=""
  for key in $DM_KEYS; do
    [ -z "$keys" ] && keys="$key" || keys="$keys $key"
  done
  for key in $keys; do
    dur=$(dm_get_ms "$key")
    name=$(dm_step_name "$key"); [ -z "$name" ] && name="$key"
    base=$(dm_baseline "$key" "$log_dir")
    level=$(dm_judge "$key" "$dur" "$(dm_step_threshold "$key")" "$base" | cut -d' ' -f1)
    bar=$(dm_bar "$dur" "$total_s")
    names="${names}${name}|${dur}|${level}|${bar}\n"
  done
  signal=$(dm_signal)
  # 部署失败 → 信号强制 CRITICAL（doing/80 §2.4：信号分级与结果一致）
  if [ "$result" = "FAILED" ]; then signal=CRITICAL; fi
  # pipefail 下 grep 无匹配会触发 set -e，|| true 兜底（全部 OK 时 details 为空是常态）
  details=$(echo "$DM_LEVELS" | tr ' ' '\n' | grep -E '=(WARN|CRITICAL)$' | sed 's/=/ /' | tr '\n' ',' | sed 's/,$//' || true)

  # 建议区：WARN/CRITICAL 步骤原因映射 + 失败诊断
  suggest=""
  if [ "$signal" != "OK" ]; then
    suggest="${suggest}  ⚠ 超阈值步骤：${details:-（无明细）}\n"
    suggest="${suggest}  可能原因：build-images→服务器网络/磁盘；rsync→本机上行带宽；smoke→LLM/TTS 响应；restart→voice 模型加载\n"
  fi
  if [ "$result" = "FAILED" ]; then
    suggest="${suggest}  修复指引（失败模式知识库）：\n"
    # 取最新部署日志（含本次失败）做失败特征匹配
    diag=$(dm_diagnose "" "$(ls -t "$log_dir"/deploy-*.log 2>/dev/null | head -1 | xargs cat 2>/dev/null || true)")
    if [ -n "$diag" ]; then suggest="${suggest}${diag}"; fi
  fi

  echo "================================================"
  echo "📦 MindSafe 部署汇报（DOC-077 监控模型）"
  echo "================================================"
  echo "组件   : ${components}"
  if [ "$result" = "SUCCESS" ]; then
    echo "结果   : ✅ SUCCESS"
  else
    echo "结果   : ❌ FAILED${fail_step:+（${fail_step}）}"
  fi
  echo "总耗时 : ${duration_s}"
  echo "┌ 步骤耗时明细 ──────────────────────────────"
  printf '%b' "$names" | while IFS='|' read -r n d l b; do
    [ -z "$n" ] && continue
    printf "│ %-12s %8.1fs %s %s\n" "$n" "$(echo "$d" | awk '{print $1/1000}')" "$l" "$b"
  done
  echo "└─────────────────────────────────────────────"
  echo "信号   : ${signal}"
  if [ -n "$details" ]; then echo "命中   : ${details}"; fi
  if [ -n "$suggest" ]; then
    echo "建议   : "
    printf '%b' "$suggest"
  fi
  echo "日志   : ${log_file:-${log_dir}/deploy-$(date +%Y%m%d-%H%M%S).log}"
  echo "================================================"
}

# ===== 部署结束编排（deploy.sh 的 trap EXIT 调用） =====
# dm_finish_deploy <rc> <components> <state_file> <log_dir> <log_file>
# 依赖全局 DM_* 计时状态；未进入部署流程（DM_IN_FLOW != 1）时静默返回（门禁拦截不汇报）
# 职责：补 end 未完成步骤（失败时最后未完成者即失败步骤）→ 总耗时 → 汇报 → 统计段 → 快照
# 放入库是为了可测试性（tests/unit/scripts/deploy-metrics-test.sh T11/T12）
dm_finish_deploy() {
  local rc=$1 components="$2" state_file="$3" log_dir="$4" log_file="$5"
  [ "${DM_IN_FLOW:-0}" != "1" ] && return 0
  local fail_step="" k key m
  for k in ${DM_START_MS}; do
    key=${k%%=*}
    case " ${DM_KEYS} " in
      *" ${key} "*) ;;
      *)
        dm_end "$key" >/dev/null 2>&1 || true
        if [ "$rc" != "0" ]; then fail_step="$key"; fi
        ;;
    esac
  done
  local result=SUCCESS
  if [ "$rc" != "0" ]; then result=FAILED; fi
  # 总耗时 = 各步骤之和（步骤顺序不重叠）
  local duration_ms=0
  for key in ${DM_KEYS}; do
    m=$(dm_get_ms "$key")
    if [ -n "$m" ]; then duration_ms=$((duration_ms + m)); fi
  done
  # 先汇报（内部完成阈值判定），再追加结构化统计段供基线解析
  dm_report "$components" "$result" "$duration_ms" "$log_dir" "$fail_step" "$log_file"
  local signal details
  signal=$(dm_signal)
  if [ "$result" = "FAILED" ]; then signal=CRITICAL; fi
  # pipefail 下 grep 无匹配会触发 set -e，|| true 兜底（全部 OK 时 details 为空是常态）
  details=$(echo "${DM_LEVELS}" | tr ' ' '\n' | grep -E '=(WARN|CRITICAL)$' | sed 's/=/ /' | tr '\n' ',' | sed 's/,$//' || true)
  dm_stats_block "$components" "$result" "$duration_ms" "$signal" "$details" >> "$log_file"
  # .deploy-state 统计快照（仅成功部署）
  if [ "$result" = "SUCCESS" ]; then
    echo "LAST_DEPLOY_DURATION_MS=${duration_ms}" >> "$state_file"
    echo "LAST_DEPLOY_SIGNAL=${signal}" >> "$state_file"
    for key in ${DM_KEYS}; do
      m=$(dm_get_ms "$key")
      echo "LAST_STEP_$(echo "$key" | tr '-' '_')_MS=${m}" >> "$state_file"
    done
  fi
}
