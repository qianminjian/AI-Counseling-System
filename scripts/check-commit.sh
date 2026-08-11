#!/usr/bin/env bash
# ============================================================
# E5: 提交质量检查（修复审计项 E5：提交粒度不均）
#
# 校验规则（与 .qoder/rules/code-engineering.md §2 Git 工作流一致）：
#   1. 消息格式：<type>(<scope>): <subject> 或 <type>: <subject>
#   2. type 白名单：feat/fix/docs/style/refactor/test/chore/perf/ci/
#                   revert/security/hotfix
#   3. subject（冒号后）≤ 50 字符
#   4. 原子提交：单提交变更文件数 ≤ 15（防大杂烩提交）
#   merge 提交自动跳过（不校验）
#
# 用法：
#   ./scripts/check-commit.sh --last <N>   校验最近 N 个提交（默认 --last 1）
#   ./scripts/check-commit.sh --staged     校验暂存区（配合 commit-msg hook）
#
# 退出码：全部通过 = 0；任一违规 = 1
# ============================================================
set -euo pipefail

TYPES="feat fix docs style refactor test chore perf ci revert security hotfix"
MAX_FILES=15
MAX_SUBJECT=50
LAST=""
STAGED=0
FAILED=0

usage() {
  cat <<'EOF'
用法: check-commit.sh [--last <N> | --staged]
  --last <N>   校验最近 N 个提交（默认 1）
  --staged     校验暂存区提交消息（用于 commit-msg hook）
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --last) LAST="$2"; shift 2 ;;
    --staged) STAGED=1; shift ;;
    --help|-h) usage; exit 0 ;;
    -*) echo "未知参数: $1" >&2; usage >&2; exit 2 ;;
    *) break ;;  # 位置参数：--staged 模式的消息文件路径
  esac
done

[ "$STAGED" -eq 0 ] && [ -z "$LAST" ] && LAST=1
[ "$STAGED" -eq 1 ] && [ -n "$LAST" ] && { echo "错误：--staged 与 --last 不能同时使用" >&2; exit 2; }

fail() { echo "  ✗ $1"; FAILED=$((FAILED + 1)); }
pass() { echo "  ✓ $1"; }

# T4 纪律（DOC-072）：Controller 层禁止注入 MyBatis Mapper（数据访问下沉领域 Service）
# 用法：check_controller_mapper <commit-hash|空> <file-path>（hash 为空时读取暂存区）
check_controller_mapper() {
  local hash="$1" f="$2" content
  case "$f" in
    backend/counseling-api/src/main/java/com/mindsafe/api/controller/*.java)
      content=$(git show "$hash:$f" 2>/dev/null || true)
      if echo "$content" | grep -qE 'import com\.mindsafe\.domain\.mapper\.'; then
        fail "$f 违反 T4 分层纪律：Controller 禁止 import MyBatis Mapper（数据访问下沉 Service）"
      elif echo "$content" | grep -E 'private final [A-Za-z]+Mapper ' | grep -qv 'ObjectMapper'; then
        fail "$f 违反 T4 分层纪律：Controller 禁止注入 MyBatis Mapper 字段（数据访问下沉 Service）"
      fi
      ;;
  esac
}

check_one() {
  local hash="$1"
  local subject files count
  subject=$(git log -1 --format='%s' "$hash")

  # merge 提交跳过
  if git rev-list --merges -1 "$hash" > /dev/null 2>&1 \
      && [ "$(git log -1 --merges --format='%H' "$hash")" = "$hash" ]; then
    pass "$hash merge 提交（跳过检查）"
    return 0
  fi

  local type="" rest=""
  # 提取 type（可选 scope）
  if echo "$subject" | grep -qE '^(feat|fix|docs|style|refactor|test|chore|perf|ci|revert|security|hotfix)(\([^)]*\))?: '; then
    type=$(echo "$subject" | sed -E 's/^([a-z]+)(\([^)]*\))?: .*/\1/')
    rest=$(echo "$subject" | sed -E 's/^[a-z]+(\([^)]*\))?: //')
  else
    fail "$hash 消息格式违规: $subject"
    return 0
  fi

  # type 白名单
  if ! echo "$TYPES" | grep -qw "$type"; then
    fail "$hash type 不在白名单: $type"
  else
    pass "$hash type 合法: $type"
  fi

  # subject 长度（按字符计）
  local len
  len=${#rest}
  if [ "$len" -gt "$MAX_SUBJECT" ]; then
    fail "$hash subject 超长（${len} > ${MAX_SUBJECT}）: $rest"
  else
    pass "$hash subject 长度 ${len} ≤ ${MAX_SUBJECT}"
  fi

  # 原子提交：文件数
  files=$(git show --name-only --format='' "$hash" | sed '/^$/d' | wc -l | tr -d ' ')
  if [ "$files" -gt "$MAX_FILES" ]; then
    fail "$hash 变更文件数超限（${files} > ${MAX_FILES}），应拆分原子提交"
  else
    pass "$hash 变更文件数 ${files} ≤ ${MAX_FILES}"
  fi

  # T4 纪律（DOC-072）：Controller 层禁止注入 MyBatis Mapper
  while IFS= read -r f; do
    [ -n "$f" ] && check_controller_mapper "$hash" "$f"
  done <<< "$(git show --name-only --format='' "$hash" | sed '/^$/d')"
}

if [ "$STAGED" -eq 1 ]; then
  # commit-msg hook 模式：校验 $1（消息文件）
  MSG_FILE="${1:-}"
  [ -f "$MSG_FILE" ] || { echo "错误：--staged 需要消息文件参数（commit-msg hook 用法: check-commit.sh --staged \$1）" >&2; exit 2; }
  SUBJECT=$(head -1 "$MSG_FILE")
  echo "== 暂存提交消息检查 =="
  echo "  消息: $SUBJECT"
  # merge 进行中检测（修复：L11 设计意图"merge 提交自动跳过"只覆盖 --last 模式，
  # --staged/commit-msg hook 路径缺失 → 拦截后无醒目告警，合并状态悬空且文档不更新）
  if [ -f "$(git rev-parse --git-path MERGE_MSG)" ]; then
    echo ""
    echo "=================================================================="
    echo "⚠️  MERGE 未完成！commit-msg hook 拦截了 merge 自动提交消息"
    echo ""
    echo "    git merge 的合并结果已暂存，但提交被中止——develop 尚未记录本次合并，"
    echo "    DESIGN-OVERVIEW/TASK-TRACKER 等文档的合并变更不会落库，CI 不会触发。"
    echo ""
    echo "    请手动完成合并提交（可审计合入内容）："
    echo "      git commit -m 'chore(main): 合并 origin/main 至 develop（<合入内容摘要>）'"
    echo ""
    echo "    如需放弃合并：git merge --abort"
    echo "=================================================================="
    exit 1
  fi
  if echo "$SUBJECT" | grep -qE '^(feat|fix|docs|style|refactor|test|chore|perf|ci|revert|security|hotfix)(\([^)]*\))?: '; then
    pass "格式合法"
  else
    fail "格式违规（应为 <type>(<scope>): <subject>）: $SUBJECT"
    echo ""
    usage >&2
    exit 1
  fi

  # T4 纪律（DOC-072）：Controller 层禁止注入 MyBatis Mapper（暂存区检查）
  echo "== T4 分层纪律检查（Controller 禁止注入 Mapper）=="
  while IFS= read -r f; do
    [ -n "$f" ] && check_controller_mapper "" "$f"
  done <<< "$(git diff --cached --name-only)"

  echo ""
  if [ "$FAILED" -eq 0 ]; then
    echo "检查通过：消息格式合规 + T4 分层纪律无违规"
    exit 0
  else
    echo "提交中止：${FAILED} 项违规（消息格式 / T4 分层纪律）"
    exit 1
  fi
fi

echo "== 提交质量检查（最近 ${LAST} 个提交）=="
HASHES=$(git rev-list -n "$LAST" HEAD 2>/dev/null || true)
if [ -z "$HASHES" ]; then
  echo "  无提交可检查（空仓库）"
  echo "检查通过：无违规"
  exit 0
fi
for h in $HASHES; do
  echo ""
  check_one "$h"
done

echo ""
if [ "$FAILED" -eq 0 ]; then
  echo "检查通过：最近 ${LAST} 个提交全部合规"
  exit 0
else
  echo "检查失败：${FAILED} 项违规（消息格式 / type 白名单 / subject 长度 / 原子粒度）"
  exit 1
fi
