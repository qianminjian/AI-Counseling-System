#!/usr/bin/env bash
# ============================================================
# E2: 数据库迁移回滚演练（修复审计项 E2：迁移无回滚演练）
#
# 约定：Flyway 迁移目录中每个 V<版本>__<名称>.sql 应配对
#       V<版本>__<名称>.rollback.sql（回滚 = 撤销该迁移的 DDL/DML）。
#
# 命令：
#   check                         校验迁移/回滚配对（默认缺配对仅警告）
#   check --strict                缺配对即退出非 0（CI 门禁用）
#   drill [--target V<n>]         输出回滚计划（从最新倒序到 target 之后）
#                                 target 缺省 = 回滚全部；默认 dry-run 只打印
#
# 安全：drill 默认 dry-run 不触碰数据库；如需真实执行须显式 --execute
#       （并要求 DB 环境变量，未配置将拒绝执行）。
#
# 用法示例：
#   ./scripts/db-rollback-drill.sh check --strict
#   ./scripts/db-rollback-drill.sh drill --target V28
# ============================================================
set -euo pipefail

DEFAULT_MIG="backend/counseling-app/src/main/resources/db/migration"
MIG_DIR="$DEFAULT_MIG"
COMMAND=""
STRICT=0
TARGET=""
EXECUTE=0

usage() {
  cat <<'EOF'
用法: db-rollback-drill.sh <check|drill> [选项]
  check [--strict]     校验迁移/回滚配对；--strict 缺配对即失败
  drill [--target V<n>] [--execute]
                       输出回滚计划（默认 dry-run 只打印）
                       --target V<n>：回滚到该版本之后（不含自身）
                       --execute：真实执行（需 DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASS）
  通用: --dir <迁移目录>（默认 backend/counseling-app/src/main/resources/db/migration）
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    check|drill) COMMAND="$1" ;;
    --strict) STRICT=1 ;;
    --target) TARGET="$2"; shift ;;
    --execute) EXECUTE=1 ;;
    --dir) MIG_DIR="$2"; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "未知参数: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

[ -z "$COMMAND" ] && { usage >&2; exit 2; }
[ -d "$MIG_DIR" ] || { echo "错误：迁移目录不存在: $MIG_DIR" >&2; exit 1; }

# ---- 收集迁移（排除 rollback 文件），按版本号数字排序 ----
UPLIST=""
for f in "$MIG_DIR"/V[0-9]*__*.sql; do
  case "$f" in
    *.rollback.sql) continue ;;
  esac
  UPLIST="$UPLIST
$f"
done
UPLIST=$(echo "$UPLIST" | sed '/^$/d' | sort -t/ -k1,1)

version_of() {
  # 输入: 路径 V33__name.sql / V33__name.rollback.sql → 输出: 33
  basename "$1" | sed -E 's/^V([0-9]+)__.*/\1/'
}

rollback_of() {
  # 输入: V33__name.sql → 输出: V33__name.rollback.sql 路径（存在则输出，否则空）
  # 注意：set -e 下命令替换要求函数总是返回 0，故用 if 而非 [ ] && echo
  local up="$1" rb
  rb="${up%.sql}.rollback.sql"
  if [ -f "$rb" ]; then
    echo "$rb"
  fi
  return 0
}

if [ "$COMMAND" = "check" ]; then
  MISSING=0
  echo "== 迁移/回滚配对校验: $MIG_DIR =="
  for up in $UPLIST; do
    rb=$(rollback_of "$up")
    if [ -n "$rb" ]; then
      echo "  ✓ $(basename "$up") → $(basename "$rb")"
    else
      echo "  ⚠ [警告] $(basename "$up") 缺少配对 rollback 文件"
      MISSING=$((MISSING + 1))
    fi
  done
  echo ""
  if [ "$MISSING" -eq 0 ]; then
    echo "校验通过：全部迁移均有回滚文件"
    exit 0
  fi
  echo "共 $MISSING 个迁移缺少回滚文件（新迁移必须带 rollback；历史欠账按需补齐）"
  [ "$STRICT" -eq 1 ] && exit 1
  echo "提示：--strict 模式下缺失将被视为失败（可用于 CI 门禁）"
  exit 0
fi

# ================= drill：生成回滚计划 =================
NUM_TARGET=""
if [ -n "$TARGET" ]; then
  NUM_TARGET=$(echo "$TARGET" | sed -E 's/^V([0-9]+).*/\1/')
  [ "$NUM_TARGET" = "$TARGET" ] && NUM_TARGET=""
  [ -z "$NUM_TARGET" ] && { echo "错误：--target 格式应为 V<n>（如 V28）" >&2; exit 2; }
fi

# 倒序（最新在前）
REV=$(echo "$UPLIST" | awk '{print}' | sort -t V -k2,2nr)

PLAN=""
PLAN_COUNT=0
for up in $REV; do
  v=$(version_of "$up")
  if [ -n "$NUM_TARGET" ]; then
    if [ "$v" -le "$NUM_TARGET" ]; then continue; fi
  fi
  rb=$(rollback_of "$up")
  if [ -z "$rb" ]; then
    echo "  ⚠ [警告] V${v} 无 rollback 文件，回滚链在此中断" >&2
    continue
  fi
  PLAN="$PLAN$rb
"
  PLAN_COUNT=$((PLAN_COUNT + 1))
done

echo "== 回滚计划（dry-run，不执行任何 SQL）=="
if [ -n "$NUM_TARGET" ]; then
  echo "目标：回滚 V${NUM_TARGET} 之后的迁移（共 $PLAN_COUNT 个回滚文件）"
else
  echo "目标：回滚全部迁移（共 $PLAN_COUNT 个回滚文件）"
fi
echo ""
[ -n "$PLAN" ] && echo "$PLAN" | sed '/^$/d' | while read -r rb; do echo "  - $rb"; done
echo ""
echo "验证方式：按计划逐文件执行 psql -f <文件>，每步后抽查关键表/列已撤销。"

if [ "$EXECUTE" -eq 1 ]; then
  for env in DB_HOST DB_PORT DB_NAME DB_USER DB_PASS; do
    [ -z "${!env:-}" ] && { echo "错误：--execute 需要环境变量 $env（未设置，拒绝执行）" >&2; exit 3; }
  done
  echo "== 真实执行模式（对 $DB_HOST:$DB_PORT/$DB_NAME）=="
  [ -z "$PLAN" ] && exit 0
  echo "$PLAN" | sed '/^$/d' | while read -r rb; do
    echo ">>> psql -f $rb"
    PGPASSWORD="$DB_PASS" psql -h "$DB_HOST" -p "$DB_PORT" -d "$DB_NAME" -U "$DB_USER" -v ON_ERROR_STOP=1 -f "$rb"
  done
fi
