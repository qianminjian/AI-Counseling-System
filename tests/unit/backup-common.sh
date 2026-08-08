#!/usr/bin/env bash
# tests/unit/backup-common.sh —— DC-002 备份接缝断言（bash 单测，无 docker 依赖）
#
# 覆盖：
#   1. backup-common.sh 导出的 DB 连接事实（容器/库/用户）
#   2. BACKUP_VOLUME 探测（环境变量覆盖优先 / mock docker 自动探测 / 空值 fail-fast）
#   3. setup-server.sh：cron 指向 deploy/backup.sh + 写入前 fail-fast + 复制 backup-common.sh
#   4. backup.sh / restore.sh：source 共享事实，不再自建 DB 事实副本
#
# 运行：bash tests/unit/backup-common.sh   （退出码 0=全绿；无 docker 也可运行，docker 已被 mock）

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMMON="$ROOT/deploy/backup-common.sh"
SETUP="$ROOT/deploy/setup-server.sh"
BACKUP_SH="$ROOT/deploy/backup.sh"
RESTORE_SH="$ROOT/deploy/restore.sh"
GUIDE="$ROOT/DEPLOY-GUIDE.md"

FAILURES=0
ok()   { echo "ok   - $1"; }
fail() { echo "FAIL - $1"; FAILURES=$((FAILURES + 1)); }

check() { # check <描述> <期望值> <实际值>
    if [ "$2" = "$3" ]; then ok "$1"; else fail "$1 (期望 [$2] 实际 [$3])"; fi
}

# ===== 1. 共享事实存在且变量导出正确 =====
if [ -f "$COMMON" ]; then
    # source 前 mock docker（防真实环境缺 docker 时探测命令失败）
    docker() { echo "deploy_dbbackups"; }
    # 注：bash 中 `VAR=x source file` 的临时赋值在 source 结束后恢复，故先用 export 再 source
    export BACKUP_VOLUME="__TEST_VOL__"
    source "$COMMON" 2>/dev/null
    check "CONTAINER_NAME 导出" "mindsafe-pg" "$CONTAINER_NAME"
    check "DB_NAME 导出" "mindsafe" "$DB_NAME"
    check "DB_USER 导出" "mindsafe" "$DB_USER"
    check "BACKUP_VOLUME 环境变量覆盖优先" "__TEST_VOL__" "$BACKUP_VOLUME"

    # 2a. 无环境变量 → mock docker 自动探测
    env -u BACKUP_VOLUME bash -c 'docker() { echo "local_deploy_dbbackups"; }; source "$1"; echo "$BACKUP_VOLUME"' _ "$COMMON" > /tmp/bc_probe.out 2>/dev/null || true
    check "无环境变量 → 自动探测 dbbackups 卷" "local_deploy_dbbackups" "$(cat /tmp/bc_probe.out)"

    # 2b. 探测为空 → detect_backup_volume fail-fast（子 shell，不杀测试进程）
    set +e
    env -u BACKUP_VOLUME bash -c 'docker() { echo ""; }; source "$1"; detect_backup_volume' _ "$COMMON" > /dev/null 2>&1
    RC=$?
    set -e
    check "探测为空 → exit 1" "1" "$RC"
    rm -f /tmp/bc_probe.out
else
    fail "backup-common.sh 不存在（待实现）"
fi

# ===== 3. setup-server.sh 修复断言 =====
if [ -f "$SETUP" ]; then
    grep -q 'deploy/backup.sh' "$SETUP" && ok "cron 指向 /guju/mindsafe/deploy/backup.sh" \
        || fail "cron 仍指向错误路径（/guju/mindsafe/backup.sh）"
    grep -qE '\[ -f .*backup\.sh.*\]' "$SETUP" && ok "cron 写入前有文件存在校验（fail-fast）" \
        || fail "缺少 cron 写入前文件校验"
    grep -q 'backup-common.sh' "$SETUP" && ok "setup-server.sh 复制 backup-common.sh" \
        || fail "setup-server.sh 未复制 backup-common.sh"
    grep -q '/guju/mindsafe/backup.sh' "$SETUP" && fail "setup-server.sh 仍残留旧 cron 路径" || ok "无旧路径残留"
else
    fail "setup-server.sh 不存在"
fi

# ===== 4. backup.sh / restore.sh 收敛断言 =====
for f in "$BACKUP_SH" "$RESTORE_SH"; do
    [ -f "$f" ] || { fail "$(basename "$f") 不存在"; continue; }
    grep -q 'backup-common.sh' "$f" && ok "$(basename "$f") source 共享事实" \
        || fail "$(basename "$f") 未 source backup-common.sh"
    grep -qE '^CONTAINER_NAME=' "$f" && fail "$(basename "$f") 仍自建 CONTAINER_NAME 副本" \
        || ok "$(basename "$f") 无 CONTAINER_NAME 副本"
    grep -qE '^BACKUP_VOLUME=' "$f" && fail "$(basename "$f") 仍自建 BACKUP_VOLUME 探测副本" \
        || ok "$(basename "$f") 无 BACKUP_VOLUME 探测副本"
done

# ===== 4b. DEPLOY-GUIDE.md cron 路径防回潮（D-03：运维排查第一入口） =====
if [ -f "$GUIDE" ]; then
    grep -q '0 2 \* \* \* /guju/mindsafe/deploy/backup\.sh' "$GUIDE" && ok "DEPLOY-GUIDE cron 指向 /guju/mindsafe/deploy/backup.sh" \
        || fail "DEPLOY-GUIDE cron 路径缺失或未指向 deploy/ 前缀"
    grep -q '/guju/mindsafe/backup.sh' "$GUIDE" && fail "DEPLOY-GUIDE 仍残留旧 cron 路径（/guju/mindsafe/backup.sh）" \
        || ok "DEPLOY-GUIDE 无旧路径残留"
else
    fail "DEPLOY-GUIDE.md 不存在"
fi

# ===== 5. 语法检查 =====
for f in "$COMMON" "$BACKUP_SH" "$RESTORE_SH" "$SETUP"; do
    if [ -f "$f" ]; then
        bash -n "$f" && ok "bash -n 通过: $(basename "$f")" || fail "bash -n 失败: $(basename "$f")"
    fi
done

echo ""
if [ "$FAILURES" -eq 0 ]; then
    echo "全部通过 ✓"
else
    echo "${FAILURES} 项失败 ✗"
fi
exit "$FAILURES"
