#!/usr/bin/env bash
# tests/unit/scripts/verify-remote-dir-test.sh —— DA-12 部署根路径单一事实源断言（bash 单测，无 docker 依赖）
#
# 覆盖：
#   1. deploy.sh 与 service-manager.sh 的 REMOTE_DIR 定义值一致（唯一事实源）
#   2. NGINX_SPECS（deploy.sh）/ COMPOSE_DIR、check_frontend_dist（service-manager.sh）均经
#      ${REMOTE_DIR} 拼接，无字面量旁路
#   3. 除定义行外，代码无残留 /guju/mindsafe 字面量（注释除外）
#
# 运行：bash tests/unit/scripts/verify-remote-dir-test.sh （退出码 0=全绿）

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
DEPLOY_SH="$ROOT/deploy.sh"
SERVICE_MANAGER_SH="$ROOT/service-manager.sh"

FAILURES=0
ok()   { echo "ok   - $1"; }
fail() { echo "FAIL - $1"; FAILURES=$((FAILURES + 1)); }

check() { # check <描述> <期望值> <实际值>
    if [ "$2" = "$3" ]; then ok "$1"; else fail "$1 (期望 [$2] 实际 [$3])"; fi
}

# ===== 1. 两脚本 REMOTE_DIR 定义一致 =====
if [ ! -f "$DEPLOY_SH" ] || [ ! -f "$SERVICE_MANAGER_SH" ]; then
    fail "deploy.sh / service-manager.sh 缺失"
    exit "$FAILURES"
fi
DEPLOY_REMOTE="$(grep -E '^REMOTE_DIR=' "$DEPLOY_SH" | head -1 | cut -d= -f2- | tr -d '"')"
SVC_REMOTE="$(grep -E '^REMOTE_DIR=' "$SERVICE_MANAGER_SH" | head -1 | cut -d= -f2- | tr -d '"')"
check "deploy.sh REMOTE_DIR 已定义" "/guju/mindsafe" "$DEPLOY_REMOTE"
check "service-manager.sh REMOTE_DIR 已定义" "/guju/mindsafe" "$SVC_REMOTE"
check "两脚本 REMOTE_DIR 一致" "$DEPLOY_REMOTE" "$SVC_REMOTE"

# ===== 2. 消费点走变量拼接，无字面量旁路 =====
grep -q 'student:\${REMOTE_DIR}/frontend/student-h5/dist/' "$DEPLOY_SH" \
    && ok "deploy.sh NGINX_SPECS student 走 \${REMOTE_DIR}" \
    || fail "deploy.sh NGINX_SPECS student 仍拼字面量"
grep -q 'teacher:\${REMOTE_DIR}/frontend/teacher-web/dist/' "$DEPLOY_SH" \
    && ok "deploy.sh NGINX_SPECS teacher 走 \${REMOTE_DIR}" \
    || fail "deploy.sh NGINX_SPECS teacher 仍拼字面量"
grep -q 'parent:\${REMOTE_DIR}/frontend/parent-h5/dist/' "$DEPLOY_SH" \
    && ok "deploy.sh NGINX_SPECS parent 走 \${REMOTE_DIR}" \
    || fail "deploy.sh NGINX_SPECS parent 仍拼字面量"
grep -q '^COMPOSE_DIR="\${REMOTE_DIR}/deploy"' "$SERVICE_MANAGER_SH" \
    && ok "service-manager.sh COMPOSE_DIR 由 \${REMOTE_DIR} 派生" \
    || fail "service-manager.sh COMPOSE_DIR 仍拼字面量"
grep -q '"\${REMOTE_DIR}/frontend/\$app/dist"' "$SERVICE_MANAGER_SH" \
    && ok "service-manager.sh check_frontend_dist 走 \${REMOTE_DIR}" \
    || fail "service-manager.sh check_frontend_dist 仍拼字面量"

# ===== 3. 代码残留字面量检查（定义行与注释除外） =====
for f in "$DEPLOY_SH" "$SERVICE_MANAGER_SH"; do
    # 提取非注释代码行中的 /guju/mindsafe 出现（去掉定义行后应为 0）
    REMAINING="$(grep -n 'guju/mindsafe' "$f" | grep -v '^\s*[0-9]*:#' | grep -v '^[0-9]*:REMOTE_DIR=' || true)"
    if [ -n "$REMAINING" ]; then
        fail "$(basename "$f") 存在代码字面量残留: $REMAINING"
    else
        ok "$(basename "$f") 无代码字面量残留（仅定义行）"
    fi
done

# ===== 4. 语法检查 =====
bash -n "$DEPLOY_SH" && ok "bash -n 通过: deploy.sh" || fail "bash -n 失败: deploy.sh"
bash -n "$SERVICE_MANAGER_SH" && ok "bash -n 通过: service-manager.sh" || fail "bash -n 失败: service-manager.sh"

echo ""
if [ "$FAILURES" -eq 0 ]; then
    echo "全部通过 ✓"
else
    echo "${FAILURES} 项失败 ✗"
fi
exit "$FAILURES"
