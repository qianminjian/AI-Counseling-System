#!/usr/bin/env bash
# ============================================================
# E1: scripts/gen-changelog.sh 行为测试（TDD）
# 验证：conventional commits 分类聚合、--since 截断、退出码
# 用法：bash tests/unit/scripts/gen-changelog-test.sh
# ============================================================
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")/../../.." && pwd)/scripts/gen-changelog.sh"
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' EXIT

PASS=0
FAIL=0
ok() { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# ---- fixture git 仓库：6 个不同类型提交 ----
REPO="$TEST_ROOT/repo"
mkdir -p "$REPO"
cd "$REPO"
git init -q
git config user.email test@test
git config user.name test
echo a > a.txt; git add a.txt; git commit -qm "feat(chat): 新增会话能力"
echo b > b.txt; git add b.txt; git commit -qm "fix(auth): 修复登录 500"
echo c > c.txt; git add c.txt; git commit -qm "docs: 更新设计文档"
echo d > d.txt; git add d.txt; git commit -qm "test: 补充单测"
echo e > e.txt; git add e.txt; git commit -qm "chore: 依赖升级"
echo f > f.txt; git add f.txt; git commit -qm "杂项提交没有前缀"
cd - > /dev/null

echo "== E1 gen-changelog 测试 =="

# ---- 1. 脚本存在且可执行 ----
[ -x "$SCRIPT" ] && ok "脚本存在且可执行" || bad "脚本缺失或不可执行: $SCRIPT"

# ---- 2. 全量输出包含 5 个分类标题 ----
OUT=$("$SCRIPT" --repo "$REPO")
for section in "新功能" "修复" "文档" "测试" "其他"; do
  echo "$OUT" | grep -q "## $section" && ok "分类标题 [$section] 存在" \
    || bad "缺少分类标题 [$section]"
done

# ---- 3. 提交按类型归入对应分类 ----
echo "$OUT" | grep -q "feat(chat): 新增会话能力" && ok "feat 提交归入新功能" || bad "feat 提交缺失"
echo "$OUT" | grep -q "fix(auth): 修复登录 500" && ok "fix 提交归入修复" || bad "fix 提交缺失"
echo "$OUT" | grep -q "test: 补充单测" && ok "test 提交归入测试" || bad "test 提交缺失"
echo "$OUT" | grep -q "杂项提交没有前缀" && ok "无前缀提交归入其他" || bad "无前缀提交缺失"

# ---- 4. --since 截断：只包含该提交之后的提交 ----
MARK=$(cd "$REPO" && git rev-parse HEAD~2)  # test: 补充单测 的 hash
OUT2=$("$SCRIPT" --repo "$REPO" --since "$MARK")
echo "$OUT2" | grep -q "feat(chat)" && bad "--since 未截断（包含旧提交）" || ok "--since 排除旧提交"
echo "$OUT2" | grep -q "chore: 依赖升级" && ok "--since 保留新提交" || bad "--since 丢失新提交"

# ---- 5. --out 写文件模式 ----
OUT_FILE="$TEST_ROOT/CHANGELOG.md"
"$SCRIPT" --repo "$REPO" --out "$OUT_FILE"
[ -s "$OUT_FILE" ] && ok "--out 输出文件非空" || bad "--out 输出文件为空"

# ---- 6. 退出码：正常生成 = 0；仓库不存在 = 非 0 ----
if "$SCRIPT" --repo "$REPO" > /dev/null 2>&1; then ok "正常生成退出码 0"; else bad "正常生成退出码非 0"; fi
if "$SCRIPT" --repo "$TEST_ROOT/nonexistent" > /dev/null 2>&1; then bad "仓库不存在应报错"; else ok "仓库不存在退出码非 0"; fi

echo ""
echo "E1 测试结果：$PASS 通过 / $FAIL 失败"
[ "$FAIL" -eq 0 ]
