#!/usr/bin/env bash
# ============================================================
# E5: scripts/check-commit.sh 行为测试（TDD）
# 验证：提交消息 Angular 格式、type 白名单、subject 长度、
#       原子提交文件数上限（粒度）
# 用法：bash tests/unit/scripts/check-commit-test.sh
# ============================================================
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")/../../.." && pwd)/scripts/check-commit.sh"
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' EXIT

PASS=0
FAIL=0
ok() { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

REPO="$TEST_ROOT/repo"
mkdir -p "$REPO"
cd "$REPO"
git init -q
git config user.email test@test
git config user.name test

echo "== E5 check-commit 测试 =="

# ---- 1. 脚本存在且可执行 ----
[ -x "$SCRIPT" ] && ok "脚本存在且可执行" || bad "脚本缺失或不可执行: $SCRIPT"

# ---- 2. 无参数 → 默认检查最近 1 个；--help 输出用法 ----
USAGE_OUT=$("$SCRIPT" --help 2>&1 || true)
echo "$USAGE_OUT" | grep -q "用法" && ok "--help 输出用法" || bad "--help 未输出用法"
if "$SCRIPT" > /dev/null 2>&1; then ok "无参数默认检查最近 1 个" ; else bad "无参数默认检查失败"; fi

# ---- 3. 合法提交（feat+scope）→ 0 ----
echo a > a.txt; git add a.txt; git commit -qm "feat(chat): 新增会话能力"
if "$SCRIPT" --last 1 > /dev/null 2>&1; then ok "合法提交退出 0"; else bad "合法提交应退出 0"; fi

# ---- 4. 非法 type → 非 0 ----
echo b > b.txt; git add b.txt; git commit -qm "nonsense: 不是合法类型"
if "$SCRIPT" --last 1 > /dev/null 2>&1; then bad "非法 type 应退出非 0"; else ok "非法 type 退出非 0"; fi

# ---- 5. 无前缀消息 → 非 0 ----
echo c > c.txt; git add c.txt; git commit -qm "直接写了个描述"
if "$SCRIPT" --last 1 > /dev/null 2>&1; then bad "无前缀应退出非 0"; else ok "无前缀退出非 0"; fi

# ---- 6. subject 超长（>50 字符）→ 非 0 ----
LONG_SUBJECT=$(python3 -c "print('fix: ' + '字'*51)" 2>/dev/null || echo "fix: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
echo d > d.txt; git add d.txt; git commit -qm "$LONG_SUBJECT"
if "$SCRIPT" --last 1 > /dev/null 2>&1; then bad "超长 subject 应退出非 0"; else ok "超长 subject 退出非 0"; fi

# ---- 7. 大提交（>15 文件）→ 非 0 ----
mkdir -p big
for i in $(seq 1 16); do echo x > "big/f$i.txt"; done
git add big/
git commit -qm "feat: 一次性改 16 个文件"
if "$SCRIPT" --last 1 > /dev/null 2>&1; then bad "16 文件提交应退出非 0"; else ok "16 文件提交退出非 0"; fi

# ---- 8. 多提交检查：混合合法/非法 → 非 0 且指出 ----
echo e > e.txt; git add e.txt; git commit -qm "docs: 更新文档"
OUT=$("$SCRIPT" --last 4 2>&1 || true)
echo "$OUT" | grep -q "直接写了个描述" && ok "批量检查指出非法提交" || bad "批量检查未指出非法提交"
if "$SCRIPT" --last 4 > /dev/null 2>&1; then bad "含非法提交应退出非 0"; else ok "含非法提交退出非 0"; fi

echo ""
echo "E5 测试结果：$PASS 通过 / $FAIL 失败"
[ "$FAIL" -eq 0 ]
