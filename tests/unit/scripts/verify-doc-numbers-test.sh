#!/usr/bin/env bash
# ============================================================
# E3: scripts/verify-doc-numbers.sh 行为测试（TDD）
# 验证：DESIGN-OVERVIEW 链接完整性、目录计数、编号连续性
# 用法：bash tests/unit/scripts/verify-doc-numbers-test.sh
# ============================================================
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")/../../.." && pwd)/scripts/verify-doc-numbers.sh"
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' EXIT

PASS=0
FAIL=0
ok() { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# ---- fixture：迷你 design 目录 ----
# 01/02/03 三份合并文档 + DESIGN-OVERVIEW（表格含 3 链接）+ frozen 2 份（34/58）
D="$TEST_ROOT/design"
mkdir -p "$D" "$D/frozen"
cat > "$D/DESIGN-OVERVIEW.md" <<'EOF'
## 合并后文档目录（3 份）
| 序号 | 主题 | 链接 |
|------|------|------|
| 01 | 概述 | [01_概述.md](01_概述.md) |
| 02 | 数据库 | [02_数据库.md](02_数据库.md) |
| 03 | 架构 | [03_架构.md](03_架构.md) |
EOF
: > "$D/01_概述.md"
: > "$D/02_数据库.md"
: > "$D/03_架构.md"
: > "$D/frozen/34_量表.md"
: > "$D/frozen/58_安全模式.md"

echo "== E3 verify-doc-numbers 测试 =="

# ---- 1. 脚本存在且可执行 ----
[ -x "$SCRIPT" ] && ok "脚本存在且可执行" || bad "脚本缺失或不可执行: $SCRIPT"

# ---- 2. 完整 fixture → 退出 0 ----
if "$SCRIPT" --design "$D" > /dev/null 2>&1; then ok "完整 fixture 退出 0"; else bad "完整 fixture 应退出 0"; fi

# ---- 3. 表格链接缺失文件 → 非 0 且提示 ----
rm "$D/02_数据库.md"
OUT=$("$SCRIPT" --design "$D" 2>&1 || true)
echo "$OUT" | grep -q "02_数据库.md" && ok "缺链接文件被指出" || bad "缺链接文件未被指出"
if "$SCRIPT" --design "$D" > /dev/null 2>&1; then bad "缺文件应退出非 0"; else ok "缺文件退出非 0"; fi
: > "$D/02_数据库.md"

# ---- 4. frozen 编号不在白名单 → 非 0 ----
cp "$D/frozen/34_量表.md" "$D/frozen/35_越权.md"
if "$SCRIPT" --design "$D" > /dev/null 2>&1; then bad "frozen 越权编号应退出非 0"; else ok "frozen 越权编号退出非 0"; fi
rm "$D/frozen/35_越权.md"

# ---- 5. 设计目录不存在 → 非 0 ----
if "$SCRIPT" --design "$TEST_ROOT/nonexistent" > /dev/null 2>&1; then bad "目录不存在应报错"; else ok "目录不存在退出非 0"; fi

echo ""
echo "E3 测试结果：$PASS 通过 / $FAIL 失败"
[ "$FAIL" -eq 0 ]
