#!/usr/bin/env bash
# ============================================================
# E1: 基于 Conventional Commits 生成 CHANGELOG.md
# 修复审计项 E1（无 changelog 自动化）：
#   - 按 type 聚合 git 历史为分类章节
#   - --since 支持增量生成（发版后只收集新提交）
#   - 输出 stdout 或 --out 指定文件
# 用法：
#   ./scripts/gen-changelog.sh                     # 当前仓库全量
#   ./scripts/gen-changelog.sh --since v1.0.0      # 增量
#   ./scripts/gen-changelog.sh --repo <path> --out CHANGELOG.md
# ============================================================
set -euo pipefail

REPO="."
SINCE=""
OUT=""

while [ $# -gt 0 ]; do
  case "$1" in
    --repo) REPO="$2"; shift 2 ;;
    --since) SINCE="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

cd "$REPO"
if ! git rev-parse --is-inside-work-tree > /dev/null 2>&1; then
  echo "错误：不是 git 仓库（$REPO）" >&2
  exit 1
fi

# type → 章节标题 映射（未列出的归"其他"；bash 3.2 兼容：不用关联数组）
section_of() {
  case "$1" in
    feat) echo "新功能" ;;
    fix) echo "修复" ;;
    security) echo "安全" ;;
    hotfix) echo "紧急修复" ;;
    docs) echo "文档" ;;
    test) echo "测试" ;;
    refactor) echo "重构" ;;
    perf) echo "性能" ;;
    chore) echo "工程维护" ;;
    style) echo "代码风格" ;;
    ci) echo "CI/CD" ;;
    revert) echo "回滚" ;;
    *) echo "其他" ;;
  esac
}

RANGE=""
if [ -n "$SINCE" ]; then
  # --since 语义：包含该提交之后的提交（git 惯例 ^since 排除自身）
  if ! git rev-parse --verify "$SINCE" > /dev/null 2>&1; then
    echo "错误：--since 不是有效引用: $SINCE" >&2
    exit 1
  fi
  RANGE="$SINCE..HEAD"
fi

# 逐行收集: <type>|<短 hash>|<完整 subject>
LOG=$(git log --format='%h|%s' --no-merges $RANGE | head -500)

if [ -z "$LOG" ]; then
  echo "## 变更记录"
  echo ""
  echo "_（无提交）_"
  exit 0
fi

declare -a GROUP_KEYS=()
declare -a GROUP_VALUES=()

BODY="## 变更记录"

while IFS= read -r line; do
  [ -z "$line" ] && continue
  hash="${line%%|*}"
  subject="${line#*|}"
  type="other"
  case "$subject" in
    feat\(*\):*|feat:*) type="feat" ;;
    fix\(*\):*|fix:*) type="fix" ;;
    security\(*\):*|security:*) type="security" ;;
    hotfix\(*\):*|hotfix:*) type="hotfix" ;;
    docs\(*\):*|docs:*) type="docs" ;;
    test\(*\):*|test:*) type="test" ;;
    refactor\(*\):*|refactor:*) type="refactor" ;;
    perf\(*\):*|perf:*) type="perf" ;;
    chore\(*\):*|chore:*) type="chore" ;;
    style\(*\):*|style:*) type="style" ;;
    ci\(*\):*|ci:*) type="ci" ;;
    revert\(*\):*|revert:*) type="revert" ;;
  esac
  title=$(section_of "$type")
  idx=-1
  for i in "${!GROUP_KEYS[@]}"; do
    if [ "${GROUP_KEYS[$i]}" = "$title" ]; then idx=$i; break; fi
  done
  entry="- ${subject} (${hash})"
  if [ "$idx" -eq -1 ]; then
    GROUP_KEYS+=("$title")
    GROUP_VALUES+=("$entry")
  else
    GROUP_VALUES[$idx]="${GROUP_VALUES[$idx]}
$entry"
  fi
done <<< "$LOG"

for i in "${!GROUP_KEYS[@]}"; do
  BODY="${BODY}\n\n### ${GROUP_KEYS[$i]}\n\n${GROUP_VALUES[$i]}"
done

if [ -n "$OUT" ]; then
  echo -e "$BODY" > "$OUT"
  echo "已生成: $OUT" >&2
else
  echo -e "$BODY"
fi

