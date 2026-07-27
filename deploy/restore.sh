#!/usr/bin/env bash
# MindSafe 数据库恢复脚本
# 用法：./restore.sh <backup_file.dump> [--force]
#
# ⚠️ 警告：此操作会覆盖当前数据库！
# 恢复前自动创建当前库快照（safety snapshot）

set -euo pipefail

CONTAINER_NAME="mindsafe-pg"
DB_NAME="mindsafe"
DB_USER="mindsafe"
BACKUP_DIR="/guju/mindsafe/backups"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

usage() {
    echo "用法: $0 <backup_file.dump> [--force]"
    echo ""
    echo "参数:"
    echo "  backup_file.dump   pg_dump 自定义格式备份文件"
    echo "  --force            跳过确认提示（用于自动化）"
    echo ""
    echo "可用备份:"
    ls -lht "${BACKUP_DIR}"/daily/*.dump 2>/dev/null | head -5 || echo "  (无日备份)"
    ls -lht "${BACKUP_DIR}"/weekly/*.dump 2>/dev/null | head -3 || echo "  (无周备份)"
    exit 1
}

# 参数检查
if [ $# -lt 1 ]; then
    usage
fi

BACKUP_FILE="$1"
FORCE="${2:-}"

if [ ! -f "${BACKUP_FILE}" ]; then
    log "ERROR: 备份文件不存在: ${BACKUP_FILE}"
    exit 1
fi

# 确认
if [ "${FORCE}" != "--force" ]; then
    echo ""
    echo "⚠️  即将用以下备份覆盖数据库 '${DB_NAME}':"
    echo "   ${BACKUP_FILE}"
    echo "   大小: $(du -h "${BACKUP_FILE}" | cut -f1)"
    echo ""
    read -p "确认恢复？(yes/no): " CONFIRM
    if [ "${CONFIRM}" != "yes" ]; then
        log "用户取消恢复"
        exit 0
    fi
fi

# 恢复前安全快照
SNAPSHOT_FILE="${BACKUP_DIR}/daily/mindsafe_pre_restore_$(date +%Y%m%d_%H%M%S).dump"
log "创建恢复前快照: ${SNAPSHOT_FILE}"
docker exec "${CONTAINER_NAME}" pg_dump -U "${DB_USER}" -d "${DB_NAME}" --format=custom --compress=9 > "${SNAPSHOT_FILE}"

# 执行恢复
log "开始恢复: ${BACKUP_FILE} → ${DB_NAME}"
docker exec -i "${CONTAINER_NAME}" pg_restore \
    -U "${DB_USER}" \
    -d "${DB_NAME}" \
    --clean \
    --if-exists \
    --no-owner \
    --no-privileges \
    --verbose \
    < "${BACKUP_FILE}" 2>&1 | tail -5

log "恢复完成"

# 验证
log "验证数据库连接..."
docker exec "${CONTAINER_NAME}" psql -U "${DB_USER}" -d "${DB_NAME}" -c "SELECT count(*) AS user_count FROM users;" 2>/dev/null && \
    log "验证通过" || log "WARNING: 验证查询失败，请手动检查"

log "===== 恢复任务结束 ====="
log "如需回滚，使用恢复前快照: ${SNAPSHOT_FILE}"
