#!/usr/bin/env bash
# tests/unit/scripts/verify-nginx-host-test.sh —— DA-13 nginx 单源化断言（bash 单测，无 docker 依赖）
#
# 覆盖：
#   1. prod compose 已删除 nginx 服务（死资产）；test/dev compose 保留（活资产）
#   2. default-ssl.conf 死资产已清除；default.conf / security-headers.conf 保留（test/dev 活资产）
#   3. deploy/nginx/host/ 版本化位存在（宿主配置唯一仓库事实源）
#   4. deploy.sh 含 sync_host_nginx：备份 → 上传 → nginx -t 门禁 → reload
#   5. 旧表述（"compose 的 nginx 服务未启用（容器 Created）"）防回潮
#
# 运行：bash tests/unit/scripts/verify-nginx-host-test.sh （退出码 0=全绿）

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
PROD_COMPOSE="$ROOT/deploy/docker-compose.prod.yml"
TEST_COMPOSE="$ROOT/deploy/docker-compose.test.yml"
DEV_COMPOSE="$ROOT/deploy/docker-compose.yml"
NGINX_DIR="$ROOT/deploy/nginx"
HOST_DIR="$NGINX_DIR/host"
DEPLOY_SH="$ROOT/deploy.sh"
SERVICE_MANAGER_SH="$ROOT/service-manager.sh"

FAILURES=0
ok()   { echo "ok   - $1"; }
fail() { echo "FAIL - $1"; FAILURES=$((FAILURES + 1)); }

check() { # check <描述> <期望值> <实际值>
    if [ "$2" = "$3" ]; then ok "$1"; else fail "$1 (期望 [$2] 实际 [$3])"; fi
}

# ===== 1. compose 服务面 =====
grep -qE '^  nginx:' "$PROD_COMPOSE" && fail "prod compose 仍含 nginx 服务（死资产）" \
    || ok "prod compose 已删除 nginx 服务"
grep -qE '^  nginx:' "$TEST_COMPOSE" && ok "test compose 保留 nginx 服务（活资产）" \
    || fail "test compose nginx 服务缺失"
grep -qE '^  nginx:' "$DEV_COMPOSE" && ok "dev compose 保留 nginx 服务（活资产）" \
    || fail "dev compose nginx 服务缺失"

# ===== 2. conf 文件面 =====
[ -f "$NGINX_DIR/default-ssl.conf" ] && fail "default-ssl.conf 死资产未清除" \
    || ok "default-ssl.conf 已清除（无消费点）"
[ -f "$NGINX_DIR/default.conf" ] && ok "default.conf 保留（test/dev compose 活资产）" \
    || fail "default.conf 缺失"
[ -f "$NGINX_DIR/security-headers.conf" ] && ok "security-headers.conf 保留（test/dev compose 活资产）" \
    || fail "security-headers.conf 缺失"

# ===== 3. 宿主配置版本化位 =====
[ -f "$HOST_DIR/README.md" ] && ok "deploy/nginx/host/ 版本化位存在（含回填指引）" \
    || fail "deploy/nginx/host/ 版本化位缺失"

# ===== 4. deploy.sh 同步通道 =====
grep -q 'sync_host_nginx()' "$DEPLOY_SH" && ok "deploy.sh 定义 sync_host_nginx" \
    || fail "deploy.sh 缺少 sync_host_nginx"
grep -q 'nginx.conf.bak-' "$DEPLOY_SH" && ok "上传前备份宿主 nginx.conf（回滚点）" \
    || fail "deploy.sh 缺少上传前备份"
grep -q '"\$SERVER:/etc/nginx/"' "$DEPLOY_SH" && ok "host/ 上传目标为宿主 /etc/nginx/" \
    || fail "deploy.sh 缺少 host/ → /etc/nginx/ 上传"
grep -q 'nginx -t' "$DEPLOY_SH" && ok "上传后 nginx -t 门禁" \
    || fail "deploy.sh 缺少 nginx -t 门禁"
grep -q 'nginx -s reload' "$DEPLOY_SH" && ok "校验通过后 nginx -s reload" \
    || fail "deploy.sh 缺少 reload"

# ===== 4.1 宿主 nginx 限流（OPS-P1-01，doing/96：生产宿主认证端点必须有限流） =====
HOST_NGINX="$HOST_DIR/nginx.conf"
grep -q 'limit_req_zone.*zone=auth_limit' "$HOST_NGINX" \
    && ok "host/nginx.conf 定义 auth_limit zone" \
    || fail "host/nginx.conf 缺少 limit_req_zone auth_limit（生产限流门禁未生效）"
for prefix in 'api/v1/voiceprint/' 'api/v1/parent/auth/' 'api/v1/auth/'; do
    # 块级断言（防"某前缀块误删限流但全文件 grep 仍绿"）：awk 进入目标 location 块后
    # 探测 limit_req，块结束（首个非 location 的 } 行）输出 OK/MISS；
    # index() 子串匹配避免 macOS awk 字符串正则对 { } 的转义差异
    if awk -v p="/$prefix" '
        index($0, "location " p " {") > 0 { inblock=1 }
        inblock && index($0, "limit_req zone=auth_limit") > 0 { found=1 }
        inblock && $0 ~ /^[[:space:]]*\}/ && index($0, "location") == 0 { print (found ? "OK" : "MISS"); inblock=0; found=0 }
    ' "$HOST_NGINX" | grep -q OK; then
        ok "host/nginx.conf 限流前缀 /$prefix"
    else
        fail "host/nginx.conf 缺少 /$prefix 限流"
    fi
done

# ===== 5. 旧表述防回潮 =====
for f in "$DEPLOY_SH" "$SERVICE_MANAGER_SH"; do
    grep -q '未启用（容器 Created）' "$f" && fail "$(basename "$f") 仍残留旧表述（compose nginx 未启用）" \
        || ok "$(basename "$f") 无旧表述残留"
done

# ===== 6. 语法检查 =====
bash -n "$DEPLOY_SH" && ok "bash -n 通过: deploy.sh" || fail "bash -n 失败: deploy.sh"
bash -n "$SERVICE_MANAGER_SH" && ok "bash -n 通过: service-manager.sh" || fail "bash -n 失败: service-manager.sh"

echo ""
if [ "$FAILURES" -eq 0 ]; then
    echo "全部通过 ✓"
else
    echo "${FAILURES} 项失败 ✗"
fi
exit "$FAILURES"
