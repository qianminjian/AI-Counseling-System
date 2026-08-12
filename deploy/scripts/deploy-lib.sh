#!/usr/bin/env bash
# ============================================================
# deploy-lib.sh —— deploy.sh 纯函数库（DA-11，doing/81）
# 供 deploy.sh source 使用；独立库保证可测试性（tests/unit/scripts/deploy-lib-test.sh）
#
# 功能：
#   deploy_map_changes   变更路径 → 组件映射（backend/student/teacher/parent/admin/tts/voice）
#   retry                带重试的统一执行器（D4 收敛 rsync/build/nginx 三处重试变体）
#   nginx_build_cmd      nginx 路径校验远程命令构建（app:path specs → ssh 命令串）
#   nginx_parse_out      nginx 校验输出解析（OK:/MISS: 行 → 状态输出 + 退出码）
#
# 约束：bash 3.2（macOS 默认）兼容；变量一律 ${var} 包裹
#       （2026-08-07 全角字符丢字节教训，deploy.sh 注释已固化）
# ============================================================

# ===== 变更路径 → 组件映射 =====
# deploy_map_changes <changed>：<changed> 为 git diff --name-only 多行文本
# 输出：空格分隔组件名，固定顺序 backend student teacher parent admin tts voice
#       （与 deploy.sh COMPONENTS 汇总顺序一致）
# 规则（原 deploy.sh L193-204，DA-11 抽取为单一事实源）：
#   backend/ 下除 tts-service/ voice-service/ 外 → backend
#   frontend/student-h5|teacher-web|parent-h5|admin-web → student/teacher/parent/admin
#   backend/tts-service|voice-service → tts/voice
#   deploy.sh 或 deploy/ 变更 → backend+tts+voice 全量（compose/nginx 配置全局生效）
deploy_map_changes() {
  local changed="$1"
  local out=""
  # backend 判定：存在 backend/ 行且至少一行不属于 tts-service/voice-service
  if [ -n "$(echo "$changed" | grep '^backend/' | grep -v '^backend/tts-service/' | grep -v '^backend/voice-service/' || true)" ]; then
    out="${out} backend"
  fi
  echo "$changed" | grep -q '^frontend/student-h5/' && out="${out} student"
  echo "$changed" | grep -q '^frontend/teacher-web/' && out="${out} teacher"
  echo "$changed" | grep -q '^frontend/parent-h5/' && out="${out} parent"
  echo "$changed" | grep -q '^frontend/admin-web/' && out="${out} admin"
  echo "$changed" | grep -q '^backend/tts-service/' && out="${out} tts"
  echo "$changed" | grep -q '^backend/voice-service/' && out="${out} voice"
  # deploy.sh / docker-compose 变更 → 全量（幂等追加，避免重复）
  if echo "$changed" | grep -qE '^(deploy\.sh|deploy/)'; then
    for c in backend tts voice; do
      case " ${out} " in
        *" ${c} "*) ;;
        *) out="${out} ${c}" ;;
      esac
    done
  fi
  # 固定顺序输出（backend student teacher parent tts voice，与 COMPONENTS 汇总一致）
  local ordered="" c
  for c in backend student teacher parent admin tts voice; do
    case " ${out} " in
      *" ${c} "*)
        if [ -z "$ordered" ]; then ordered="$c"; else ordered="${ordered} ${c}"; fi
        ;;
    esac
  done
  echo "$ordered"
}

# ===== 带重试的统一执行器（D4：收敛 rsync/build/nginx 三处重试变体） =====
# 用法: retry <最大次数> <重试间隔秒> <命令...>
# 间隔传 0 时使用递增退避（1s, 2s, 3s...）；警告走 stderr（不污染命令 stdout 捕获）
retry() {
  local max_attempts="$1" delay="$2"
  shift 2
  local attempt=1
  while [ "$attempt" -le "$max_attempts" ]; do
    if "$@"; then
      return 0
    fi
    if [ "$attempt" -eq "$max_attempts" ]; then
      break
    fi
    local wait_secs="$delay"
    [ "$delay" -eq 0 ] && wait_secs="$attempt"
    echo "⚠️  第 ${attempt} 次失败，${wait_secs}s 后重试: $*" >&2
    sleep "$wait_secs"
    attempt=$((attempt + 1))
  done
  echo "❌ 重试 ${max_attempts} 次后仍失败: $*" >&2
  return 1
}

# ===== nginx 路径校验（2026-08-06 切换教训固化） =====
# nginx_build_cmd <specs>：specs 为空格分隔的 app:path 列表
#   （如 "student:/guju/mindsafe/frontend/student-h5/dist/"）
# 输出：单条 ssh 可执行命令串（每 spec 一条 grep -q，OK:/MISS: 标记）
nginx_build_cmd() {
  local specs="$1"
  local cmd="" spec app dir
  for spec in $specs; do
    app="${spec%%:*}"
    dir="${spec#*:}"
    cmd="${cmd} if grep -q '${dir}' /etc/nginx/nginx.conf; then echo 'OK:${app}'; else echo 'MISS:${app}'; fi;"
  done
  echo "$cmd"
}

# nginx_parse_out <out>：解析校验输出（OK:/MISS: 行）
# stdout 输出 ✅/⚠️ 状态行；返回 0=全部指向通过，1=存在 MISS
nginx_parse_out() {
  local out="$1"
  local miss="" line
  while read -r line; do
    case "$line" in
      OK:*)   echo "✅ nginx ${line#OK:} 路径指向校验通过" ;;
      MISS:*) miss="${miss} ${line#MISS:}" ;;
    esac
  done <<< "$out"
  if [ -n "$miss" ]; then
    echo "⚠️  nginx 未指向部署目标：${miss}——前端可能仍在服务旧目录！"
    return 1
  fi
  return 0
}
