#!/usr/bin/env bash
# MindSafe 数据库自动备份脚本
# 部署位置：服务器 /guju/mindsafe/backup.sh
# 定时任务：crontab -e → 0 2 * * * /guju/mindsafe/backup.sh >> /guju/mindsafe/logs/backup.log 2>&1
#
# 功能：
#   1. pg_dump 全库备份（自定义格式，支持并行恢复）
#   2. 保留最近 7 天日备份 + 最近 4 周周备份 + 最近 3 月月备份
#   3. 备份完成后校验文件完整性
#   4. 可选：rsync 到异地（配置 REMOTE_BACKUP_HOST）

set -euo pipefail

# ===== 配置 =====
BACKUP_DIR="/guju/mindsafe/backups"
LOG_DIR="/guju/mindsafe/logs"
CONTAINER_NAME="mindsafe-pg"
DB_NAME="mindsafe"
DB_USER="mindsafe"
RETAIN_DAILY=7
RETAIN_WEEKLY=4
RETAIN_MONTHLY=3

# 异地备份（可选，留空则跳过）
REMOTE_BACKUP_HOST="${REMOTE_BACKUP_HOST:-}"
REMOTE_BACKUP_DIR="${REMOTE_BACKUP_DIR:-/guju/mindsafe/backups}"

# ===== 初始化 =====
mkdir -p "${BACKUP_DIR}/daily" "${BACKUP_DIR}/weekly" "${BACKUP_DIR}/monthly" "${LOG_DIR}"

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
DAY_OF_WEEK=$(date +%u)  # 1=Monday, 7=Sunday
DAY_OF_MONTH=$(date +%d)

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

# ===== 执行备份 =====
BACKUP_FILE="${BACKUP_DIR}/daily/mindsafe_${TIMESTAMP}.dump"

log "开始备份: ${DB_NAME} → ${BACKUP_FILE}"

docker exec "${CONTAINER_NAME}" pg_dump \
    -U "${DB_USER}" \
    -d "${DB_NAME}" \
    --format=custom \
    --compress=9 \
    --verbose \
    > "${BACKUP_FILE}"

# 校验备份文件
if [ ! -s "${BACKUP_FILE}" ]; then
    log "ERROR: 备份文件为空，备份失败！"
    exit 1
fi

FILESIZE=$(du -h "${BACKUP_FILE}" | cut -f1)
log "备份完成: ${BACKUP_FILE} (${FILESIZE})"

# 校验备份可恢复性（列出 TOC）
if docker exec -i "${CONTAINER_NAME}" pg_restore --list < "${BACKUP_FILE}" > /dev/null 2>&1; then
    log "备份完整性校验通过"
else
    log "WARNING: 备份完整性校验失败，文件可能损坏"
fi

# ===== 周备份（每周日） =====
if [ "${DAY_OF_WEEK}" = "7" ]; then
    WEEKLY_FILE="${BACKUP_DIR}/weekly/mindsafe_week_${TIMESTAMP}.dump"
    cp "${BACKUP_FILE}" "${WEEKLY_FILE}"
    log "周备份: ${WEEKLY_FILE}"
fi

# ===== 月备份（每月 1 日） =====
if [ "${DAY_OF_MONTH}" = "01" ]; then
    MONTHLY_FILE="${BACKUP_DIR}/monthly/mindsafe_month_${TIMESTAMP}.dump"
    cp "${BACKUP_FILE}" "${MONTHLY_FILE}"
    log "月备份: ${MONTHLY_FILE}"
fi

# ===== 清理过期备份 =====
log "清理过期备份..."
find "${BACKUP_DIR}/daily" -name "mindsafe_*.dump" -mtime +${RETAIN_DAILY} -delete
find "${BACKUP_DIR}/weekly" -name "mindsafe_week_*.dump" -mtime +$((RETAIN_WEEKLY * 7)) -delete
find "${BACKUP_DIR}/monthly" -name "mindsafe_month_*.dump" -mtime +$((RETAIN_MONTHLY * 30)) -delete

# ===== 异地同步（可选） =====
if [ -n "${REMOTE_BACKUP_HOST}" ]; then
    log "同步到异地: ${REMOTE_BACKUP_HOST}"
    rsync -az --delete "${BACKUP_DIR}/" "${REMOTE_BACKUP_HOST}:${REMOTE_BACKUP_DIR}/"
    log "异地同步完成"
fi

# ===== 统计 =====
TOTAL_BACKUPS=$(find "${BACKUP_DIR}" -name "*.dump" | wc -l)
TOTAL_SIZE=$(du -sh "${BACKUP_DIR}" | cut -f1)
log "当前备份统计: ${TOTAL_BACKUPS} 个文件, 共 ${TOTAL_SIZE}"
log "===== 备份任务结束 ====="
