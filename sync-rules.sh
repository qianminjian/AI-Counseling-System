#!/bin/bash
# =============================================================
# Qoder 项目规则同步脚本
# =============================================================
# 在项目根目录下运行，将中央规则库按约定格式复制到本项目
#
# 用法：
#   cd /path/to/your/project
#   /path/to/sync-rules.sh
#
# 可选参数：
#   --force    强制覆盖本地已有规则文件
# =============================================================

set -euo pipefail

# ---------- 配置 ----------
RULES_SOURCE="$HOME/Documents/.LIB_ALL_PUB/Qoder/.qoder/rules"
AGENTS_SOURCE="$HOME/Documents/.LIB_ALL_PUB/Qoder/AGENTS.md"

# ---------- 颜色 ----------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# ---------- 目标检测 ----------
CURRENT_DIR=$(pwd)
PROJECT_NAME=$(basename "$CURRENT_DIR")
TARGET_RULES="$CURRENT_DIR/.qoder/rules"
TARGET_AGENTS="$CURRENT_DIR/AGENTS.md"

# ---------- 参数 ----------
FORCE=false
for arg in "$@"; do
  case "$arg" in
    --force) FORCE=true ;;
    --help|-h)
      echo "用法: cd 项目目录 && /path/to/sync-rules.sh [--force]"
      echo ""
      echo "  默认行为：仅补充缺失文件或更新更旧的本地文件"
      echo "  --force：强制覆盖本地所有规则文件"
      exit 0
      ;;
    *)
      echo -e "${RED}未知参数: $arg${NC}"
      exit 1
      ;;
  esac
done

# ---------- 校验 ----------
if [ ! -d "$RULES_SOURCE" ]; then
  echo -e "${RED}错误：规则源目录不存在${NC}"
  echo "  $RULES_SOURCE"
  exit 1
fi

if [ ! -f "$CURRENT_DIR/.qoder/rules" ] && [ ! -d "$CURRENT_DIR/.qoder/rules" ]; then
  echo -e "${YELLOW}警告：当前目录不是 Qoder 项目（缺少 .qoder/rules/ 目录）${NC}"
  echo -e "将为你创建 ${BLUE}.qoder/rules/${NC} 目录"
  echo ""
fi

# 列出源文件
# 兼容 macOS (bash 3.2) 和 Linux
SOURCE_FILES=()
while IFS= read -r f; do
  SOURCE_FILES+=("$f")
done < <(ls "$RULES_SOURCE"/*.md 2>/dev/null || true)
RULE_COUNT=${#SOURCE_FILES[@]}
HAS_AGENTS=false
[ -f "$AGENTS_SOURCE" ] && HAS_AGENTS=true

echo ""
echo "=========================================="
echo "  项目: ${BLUE}$PROJECT_NAME${NC}"
echo "  路径: $CURRENT_DIR"
echo "=========================================="
echo ""
echo -e "${YELLOW}以下规则将从中央库同步到本项目：${NC}"
echo ""
for f in "${SOURCE_FILES[@]}"; do
  echo "  • $(basename "$f")"
done
[ "$HAS_AGENTS" = true ] && echo "  • AGENTS.md"
echo ""

# -------- 检查本地已有文件 ----------
NEW_COUNT=0
UPDATE_COUNT=0
SAME_COUNT=0
SKIP_COUNT=0

declare -a TO_COPY_FILES=()

for f in "${SOURCE_FILES[@]}"; do
  fname=$(basename "$f")
  target="$TARGET_RULES/$fname"
  exists=false
  if [ -f "$target" ]; then
    exists=true
  fi

  if [ "$FORCE" = true ]; then
    # 强制模式：全部覆盖
    if [ "$exists" = true ]; then
      ((UPDATE_COUNT++))
    else
      ((NEW_COUNT++))
    fi
    TO_COPY_FILES+=("$f")
  else
    # 智能模式：缺失的或源更新的
    if [ ! -f "$target" ]; then
      ((NEW_COUNT++))
      TO_COPY_FILES+=("$f")
    elif [ "$f" -nt "$target" ]; then
      ((UPDATE_COUNT++))
      TO_COPY_FILES+=("$f")
    else
      ((SAME_COUNT++))
    fi
  fi
done

echo "------------- 变更摘要 -------------"
echo "  新增:    $NEW_COUNT"
echo "  更新:    $UPDATE_COUNT"
echo "  已一致:  $SAME_COUNT"
echo ""

# 如果 AGENTS.md 也需要拷贝
AGENTS_ACTION=""
if [ "$HAS_AGENTS" = true ]; then
  if [ "$FORCE" = true ] || [ ! -f "$TARGET_AGENTS" ] || [ "$AGENTS_SOURCE" -nt "$TARGET_AGENTS" ]; then
    AGENTS_ACTION="将同步"
  else
    AGENTS_ACTION="已一致"
  fi
  echo "  AGENTS.md: $AGENTS_ACTION"
  echo ""
fi

# 无事可做则退出
if [ "${#TO_COPY_FILES[@]}" -eq 0 ] && [ "$AGENTS_ACTION" != "将同步" ]; then
  echo -e "${GREEN}所有规则文件已是最新，无需操作。${NC}"
  exit 0
fi

echo "-----------------------------------"

# ---------- 用户确认 ----------
echo ""
read -p "$(echo -e "${YELLOW}确定将以上规则同步到「${PROJECT_NAME}」项目? (y/N): ${NC}")" CONFIRM
if [ "$CONFIRM" != "y" ] && [ "$CONFIRM" != "Y" ]; then
  echo -e "${RED}已取消。${NC}"
  exit 0
fi

# ---------- 执行同步 ----------
echo ""
echo -e "${BLUE}正在同步...${NC}"

mkdir -p "$TARGET_RULES"

CP_OK=true
for f in "${TO_COPY_FILES[@]}"; do
  fname=$(basename "$f")
  if cp "$f" "$TARGET_RULES/$fname" 2>/dev/null; then
    echo -e "  ${GREEN}✓${NC} $fname"
  else
    echo -e "  ${RED}✗${NC} $fname (拷贝失败)"
    CP_OK=false
  fi
done

if [ "$AGENTS_ACTION" = "将同步" ]; then
  if cp "$AGENTS_SOURCE" "$TARGET_AGENTS" 2>/dev/null; then
    echo -e "  ${GREEN}✓${NC} AGENTS.md"
  else
    echo -e "  ${RED}✗${NC} AGENTS.md (拷贝失败)"
    CP_OK=false
  fi
fi

# ---------- 文件一致性检测 ----------
echo ""
echo -e "${BLUE}正在进行文件一致性检测...${NC}"
echo ""

ALL_OK=true
TOTAL_BYTES_SOURCE=0
TOTAL_BYTES_TARGET=0
MATCH_COUNT=0
MISMATCH_COUNT=0
MISSING_COUNT=0

for f in "${SOURCE_FILES[@]}"; do
  fname=$(basename "$f")
  target="$TARGET_RULES/$fname"

  if [ ! -f "$target" ]; then
    echo -e "  ${RED}✗ 缺失: $fname${NC}"
    ((MISSING_COUNT++))
    ALL_OK=false
    continue
  fi

  src_size=$(wc -c < "$f" | tr -d ' ')
  tgt_size=$(wc -c < "$target" | tr -d ' ')
  if command -v md5 >/dev/null 2>&1; then
    src_md5=$(md5 -q "$f" 2>/dev/null)
    tgt_md5=$(md5 -q "$target" 2>/dev/null)
  elif command -v md5sum >/dev/null 2>&1; then
    src_md5=$(md5sum "$f" | cut -d' ' -f1)
    tgt_md5=$(md5sum "$target" | cut -d' ' -f1)
  else
    src_md5=$(openssl md5 < "$f" | cut -d' ' -f2)
    tgt_md5=$(openssl md5 < "$target" | cut -d' ' -f2)
  fi

  TOTAL_BYTES_SOURCE=$((TOTAL_BYTES_SOURCE + src_size))
  TOTAL_BYTES_TARGET=$((TOTAL_BYTES_TARGET + tgt_size))

  if [ "$src_md5" = "$tgt_md5" ]; then
    echo -e "  ${GREEN}✓${NC} $fname (${src_size}B)"
    ((MATCH_COUNT++))
  else
    echo -e "  ${RED}✗ 不一致: $fname${NC}"
    echo -e "    源: $src_md5 (${src_size}B)"
    echo -e "    目标: $tgt_md5 (${tgt_size}B)"
    ((MISMATCH_COUNT++))
    ALL_OK=false
  fi
done

# 检测 AGENTS.md
if [ "$HAS_AGENTS" = true ]; then
  if [ ! -f "$TARGET_AGENTS" ]; then
    echo -e "  ${RED}✗ 缺失: AGENTS.md${NC}"
    ((MISSING_COUNT++))
    ALL_OK=false
  else
    if command -v md5 >/dev/null 2>&1; then
      src_md5=$(md5 -q "$AGENTS_SOURCE" 2>/dev/null)
      tgt_md5=$(md5 -q "$TARGET_AGENTS" 2>/dev/null)
    elif command -v md5sum >/dev/null 2>&1; then
      src_md5=$(md5sum "$AGENTS_SOURCE" | cut -d' ' -f1)
      tgt_md5=$(md5sum "$TARGET_AGENTS" | cut -d' ' -f1)
    else
      src_md5=$(openssl md5 < "$AGENTS_SOURCE" | cut -d' ' -f2)
      tgt_md5=$(openssl md5 < "$TARGET_AGENTS" | cut -d' ' -f2)
    fi
    if [ "$src_md5" = "$tgt_md5" ]; then
      echo -e "  ${GREEN}✓${NC} AGENTS.md"
      ((MATCH_COUNT++))
    else
      echo -e "  ${RED}✗ 不一致: AGENTS.md${NC}"
      ((MISMATCH_COUNT++))
      ALL_OK=false
    fi
  fi
fi

# ---------- 检测多余文件 ----------
echo ""
echo -e "${BLUE}检查多余文件...${NC}"
EXTRA_COUNT=0
if [ -d "$TARGET_RULES" ]; then
  for tf in "$TARGET_RULES"/*.md; do
    [ -f "$tf" ] || continue
    fname=$(basename "$tf")
    found=false
    for sf in "${SOURCE_FILES[@]}"; do
      if [ "$fname" = "$(basename "$sf")" ]; then
        found=true
        break
      fi
    done
    if [ "$found" = false ]; then
      echo -e "  ${YELLOW}⚠ 本地多余: $fname${NC}"
      ((EXTRA_COUNT++))
    fi
  done
fi

# ---------- 结果 ----------
echo ""
echo "=========================================="
if [ "$ALL_OK" = true ]; then
  echo -e "  ${GREEN}✔ 一致性检测通过${NC}"
else
  echo -e "  ${RED}✘ 一致性检测发现问题${NC}"
fi
echo "  匹配: $MATCH_COUNT"
echo "  不一致: $MISMATCH_COUNT"
echo "  缺失: $MISSING_COUNT"
echo "  多余: $EXTRA_COUNT"
if [ "$ALL_OK" = true ] && [ "$MISMATCH_COUNT" -eq 0 ] && [ "$MISSING_COUNT" -eq 0 ]; then
  total_files=$RULE_COUNT
  [ "$HAS_AGENTS" = true ] && total_files=$((total_files + 1))
  echo -e "  共 ${GREEN}$total_files 个文件完全一致${NC}"
fi
echo "=========================================="
echo ""

# ---------- 项目级规则列表 ----------
echo -e "${BLUE}当前项目规则清单 (${TARGET_RULES}):${NC}"
if [ -d "$TARGET_RULES" ]; then
  for f in "$TARGET_RULES"/*.md; do
    [ -f "$f" ] || continue
    fname=$(basename "$f")
    # 读取 trigger 类型
    trigger=$(head -5 "$f" | grep -E '^trigger:' | sed 's/^trigger:[[:space:]]*//' | head -1)
    size=$(wc -c < "$f" | tr -d ' ')
    echo "  • $fname  [${trigger:-unknown}]  (${size}B)"
  done
fi
if [ -f "$TARGET_AGENTS" ]; then
  echo "  • AGENTS.md  [入口文件]"
fi

echo ""
[ "$CP_OK" = false ] && echo -e "${RED}部分文件拷贝失败，请检查权限。${NC}" && exit 1
[ "$ALL_OK" = false ] && echo -e "${RED}文件一致性检测未通过，建议重新运行。${NC}" && exit 1
echo -e "${GREEN}规则同步完成。${NC}"
