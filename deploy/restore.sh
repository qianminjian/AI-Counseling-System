#!/usr/bin/env bash
# MindSafe 数据库恢复脚本
# 用法：./restore.sh <备份路径> [--force]
#
# 备份路径两种形式：
#   1. volume 相对路径：daily/mindsafe_20260728_020000.dump（backup.sh 写入的 dbbackups volume）
#   2. 宿主机绝对路径：/guju/mindsafe/backups/daily/xxx.dump（存在的宿主机文件）
#
# ⚠️ 警告：此操作会覆盖当前数据库！
# 恢复前自动创建当前库快照（safety snapshot，写入同一 volume）

set -euo pipefail

# DB 连接事实 / dbbackups 卷探测 / log() 来自 backup-common.sh（DC-002：单一事实源，与 backup.sh 共享）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/backup-common.sh"

# 验证 schema 参数化（D6：多租户库默认 tenant_template，可用 TENANT_SCHEMA 环境变量覆盖）
TENANT_SCHEMA="${TENANT_SCHEMA:-tenant_template}"

usage() {
    echo "用法: $0 <备份路径> [--force]"
    echo ""
    echo "参数:"
    echo "  备份路径   volume 相对路径（如 daily/mindsafe_xxx.dump）或宿主机文件绝对路径"
    echo "  --force    跳过确认提示（用于自动化）"
    echo ""
    echo "dbbackups volume 中可用备份:"
    docker run --rm -v "${BACKUP_VOLUME}:/backups" -w /backups "${PG_IMAGE}" \
        sh -c 'ls -lht daily/*.dump weekly/*.dump monthly/*.dump 2>/dev/null' | head -8 || echo "  (无备份)"
    exit 1
}

# 参数检查
if [ $# -lt 1 ]; then
    usage
fi

BACKUP_PATH="$1"
FORCE="${2:-}"

# 判定备份来源：宿主机文件 or volume 相对路径
if [ -f "${BACKUP_PATH}" ]; then
    SOURCE="host"
elif docker run --rm -v "${BACKUP_VOLUME}:/backups" -w /backups "${PG_IMAGE}" \
        test -f "${BACKUP_PATH}" 2>/dev/null; then
    SOURCE="volume"
else
    log "ERROR: 备份不存在: ${BACKUP_PATH}（宿主机与 dbbackups volume 均未找到）"
    usage
fi

# 确认
if [ "${FORCE}" != "--force" ]; then
    echo ""
    echo "⚠️  即将用以下备份覆盖数据库 '${DB_NAME}'（来源: ${SOURCE}）:"
    echo "   ${BACKUP_PATH}"
    echo ""
    read -p "确认恢复？(yes/no): " CONFIRM
    if [ "${CONFIRM}" != "yes" ]; then
        log "用户取消恢复"
        exit 0
    fi
fi

# 恢复前安全快照（写入 volume daily/ 目录，与备份约定一致，供回滚）
SNAPSHOT_NAME="mindsafe_pre_restore_$(date +%Y%m%d_%H%M%S).dump"
log "创建恢复前快照: volume ${BACKUP_VOLUME}:/daily/${SNAPSHOT_NAME}"
docker exec "${CONTAINER_NAME}" pg_dump -U "${DB_USER}" -d "${DB_NAME}" --format=custom --compress=9 \
    | docker run --rm -i -v "${BACKUP_VOLUME}:/backups" -w /backups "${PG_IMAGE}" \
        sh -c 'mkdir -p daily && cat > daily/'"${SNAPSHOT_NAME}"

# 执行恢复（--exit-on-error 防止半途而废留下不一致库）
log "开始恢复: ${BACKUP_PATH} → ${DB_NAME}"
if [ "${SOURCE}" = "volume" ]; then
    docker run --rm -v "${BACKUP_VOLUME}:/backups" -w /backups "${PG_IMAGE}" \
        cat "${BACKUP_PATH}" \
        | docker exec -i "${CONTAINER_NAME}" pg_restore \
            -U "${DB_USER}" \
            -d "${DB_NAME}" \
            --clean \
            --if-exists \
            --no-owner \
            --no-privileges \
            --exit-on-error \
            --verbose 2>&1 | tail -5
else
    docker exec -i "${CONTAINER_NAME}" pg_restore \
        -U "${DB_USER}" \
        -d "${DB_NAME}" \
        --clean \
        --if-exists \
        --no-owner \
        --no-privileges \
        --exit-on-error \
        --verbose \
        < "${BACKUP_PATH}" 2>&1 | tail -5
fi

log "恢复完成"

# 验证
log "验证数据库连接..."
# fix-deploy：users 表在 tenant_template schema 中，需限定 schema（D6：schema 已参数化）
docker exec "${CONTAINER_NAME}" psql -U "${DB_USER}" -d "${DB_NAME}" -c "SELECT count(*) AS user_count FROM ${TENANT_SCHEMA}.users;" 2>/dev/null && \
    log "验证通过" || log "WARNING: 验证查询失败，请手动检查"

log "===== 恢复任务结束 ====="
log "如需回滚，使用恢复前快照: daily/${SNAPSHOT_NAME}（dbbackups volume 内）"
