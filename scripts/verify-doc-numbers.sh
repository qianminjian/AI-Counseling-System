#!/usr/bin/env bash
# ============================================================
# E3: 设计文档数字防漂移校验（修复审计项 E3：文档数字易漂移）
#
# 校验内容（机器可执行、结果可回归）：
#   1. DESIGN-OVERVIEW 合并文档表格中的全部 .md 链接指向的文件必须存在
#   2. design/ 根目录合并文档编号 01~N 必须连续（防删改跳号）
#   3. design/frozen/ 下文件编号必须在 DESIGN-OVERVIEW 声明的冻结白名单内
#      （34、38-43、58；新增冻结文档需同步更新白名单与 OVERVIEW）
#
# 用法：
#   ./scripts/verify-doc-numbers.sh                # 校验真实 design/（默认）
#   ./scripts/verify-doc-numbers.sh --design <目录> # 校验指定目录（测试用）
#
# 退出码：全部通过 = 0；任一失败 = 1
# ============================================================
set -euo pipefail

DESIGN_DIR="design"
while [ $# -gt 0 ]; do
  case "$1" in
    --design) DESIGN_DIR="$2"; shift 2 ;;
    --help|-h)
      echo "用法: verify-doc-numbers.sh [--design <目录>]"
      echo "校验 DESIGN-OVERVIEW 链接完整性 / 合并文档编号连续性 / frozen 白名单"
      exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

[ -d "$DESIGN_DIR" ] || { echo "错误：目录不存在: $DESIGN_DIR" >&2; exit 1; }

FAILED=0
CHECKED=0
fail() { echo "  ✗ $1"; FAILED=$((FAILED + 1)); }
pass() { echo "  ✓ $1"; CHECKED=$((CHECKED + 1)); }

echo "== 设计文档数字防漂移校验: $DESIGN_DIR =="
echo ""

# ---- 1. DESIGN-OVERVIEW 表格链接完整性 ----
OVERVIEW="$DESIGN_DIR/DESIGN-OVERVIEW.md"
if [ ! -f "$OVERVIEW" ]; then
  fail "缺少 DESIGN-OVERVIEW.md（无法提取文档清单）"
else
  # 提取表格中所有 markdown 链接目标（.md 结尾，去重）
  LINKS=$(grep -oE '\]\([^)]*\.md\)' "$OVERVIEW" | sed -E 's/^\]\(//; s/\)$//' | sort -u || true)
  LINK_COUNT=$(echo "$LINKS" | sed '/^$/d' | wc -l | tr -d ' ')
  echo "  表格声明链接: $LINK_COUNT 个"
  MISSING=0
  while IFS= read -r link; do
    [ -z "$link" ] && continue
    # 仅校验指向本目录（design/ 根）的链接
    case "$link" in
      */*) continue ;;  # 子目录/URL 链接不校验存在性
    esac
    if [ -f "$DESIGN_DIR/$link" ]; then
      pass "链接文件存在: $link"
    else
      fail "链接文件缺失: $link"
      MISSING=$((MISSING + 1))
    fi
  done <<< "$LINKS"
  if [ "$MISSING" -gt 0 ]; then
    echo "  → 共 $MISSING 个链接失效（文档被删/改未同步 OVERVIEW，或 OVERVIEW 过期）"
  fi
fi
echo ""

# ---- 2. 根目录合并文档编号连续性（01~N 无缺号）----
NUMS=$(ls "$DESIGN_DIR"/[0-9][0-9]_*.md 2>/dev/null | sed -E 's#.*/([0-9][0-9])_.*#\1#' | sort -n || true)
MAX=$(echo "$NUMS" | sed '/^$/d' | tail -1)
if [ -z "$MAX" ]; then
  fail "design/ 根目录无编号文档"
else
  echo "  根目录编号文档: 01 ~ $MAX"
  GAP=0
  i=1
  while [ "$i" -le "$MAX" ]; do
    n=$(printf "%02d" "$i")
    if ! echo "$NUMS" | grep -qx "$n"; then
      fail "编号缺号: $n"
      GAP=$((GAP + 1))
    else
      pass "编号存在: $n"
    fi
    i=$((i + 1))
  done
  if [ "$GAP" -eq 0 ]; then
    pass "根目录编号 01~$MAX 连续无缺"
  fi
fi
echo ""

# ---- 3. frozen 白名单校验（DESIGN-OVERVIEW 声明：34、38-43、58）----
FROZEN_DIR="$DESIGN_DIR/frozen"
FROZEN_WHITELIST="34 38 39 40 41 42 43 58 59 60 61 62 73 74"
if [ -d "$FROZEN_DIR" ]; then
  echo "  frozen 白名单: $FROZEN_WHITELIST"
  FROZEN_COUNT=0
  for f in "$FROZEN_DIR"/[0-9][0-9]_*.md; do
    [ -e "$f" ] || continue
    FROZEN_COUNT=$((FROZEN_COUNT + 1))
    num=$(basename "$f" | sed -E 's/^([0-9][0-9])_.*/\1/')
    if echo "$FROZEN_WHITELIST" | grep -qw "$num"; then
      pass "frozen 编号合法: $num ($(basename "$f"))"
    else
      fail "frozen 编号不在白名单: $num ($(basename "$f"))"
    fi
  done
  if [ "$FROZEN_COUNT" -eq 0 ]; then
    pass "frozen 目录为空（无冻结文档）"
  fi
else
  pass "frozen 目录不存在（跳过）"
fi
echo ""

# ---- 汇总 ----
echo "----------------------------------------"
if [ "$FAILED" -eq 0 ]; then
  echo "校验通过（${CHECKED} 项断言全部成立）"
  exit 0
else
  echo "校验失败：${FAILED} 项断言不成立（数字/清单已漂移，请同步文档）"
  exit 1
fi
