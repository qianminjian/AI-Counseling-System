#!/usr/bin/env bash
# MindSafe 数据库备份脚本（宿主机手动/cron 触发）
# 部署位置：服务器 /guju/mindsafe/backup.sh
# 定时任务：crontab -e → 0 2 * * * /guju/mindsafe/backup.sh >> /guju/mindsafe/logs/backup.log 2>&1
#
# 链路对齐（OPS）：
#   - 备份统一写入 compose db-backup 服务的 dbbackups volume（与自动备份容器同目录），
#     restore.sh 也从同一 volume 读取，避免"备在宿主机、恢复找不到"的断裂。
#   - 依赖：docker compose -f docker-compose.prod.yml 已启动（mindsafe-pg 容器存在）。
#
# 功能：
#   1. pg_dump 全库备份（自定义格式，支持并行恢复）
#   2. 保留最近 7 天日备份 + 最近 4 周周备份 + 最近 3 月月备份
#   3. 备份完成后校验文件完整性（pg_restore --list）
#   4. 可选：rsync 到异地（配置 REMOTE_BACKUP_HOST）

set -euo pipefail

# ===== 配置 =====
CONTAINER_NAME="mindsafe-pg"
# compose 命名卷实际带项目名前缀（如 deploy_dbbackups），硬编码 dbbackups 会挂到另一个空卷。
# 优先用环境变量 BACKUP_VOLUME 覆盖，否则自动探测宿主机上匹配 *_dbbackups 的卷。
BACKUP_VOLUME="${BACKUP_VOLUME:-$(docker volume ls --format '{{.Name}}' | grep -E '(^|_)dbbackups$' | head -1)}"
if [ -z "${BACKUP_VOLUME}" ]; then
    echo "ERROR: 未找到 dbbackups 卷（请先 docker compose -f docker-compose.prod.yml up -d，或设 BACKUP_VOLUME=<卷名>）"
    exit 1
fi
DB_NAME="mindsafe"
DB_USER="mindsafe"
RETAIN_DAILY=7
RETAIN_WEEKLY=4
RETAIN_MONTHLY=3
# 本地暂存（校验 + 异地同步用，volume 内为唯一事实源）
# fix-deploy：路径从硬编码改为可配置（默认保持兼容），便于多机器部署
STAGING_DIR="${BACKUP_STAGING_DIR:-/guju/mindsafe/backups}"
LOG_DIR="${BACKUP_LOG_DIR:-/guju/mindsafe/logs}"

# 异地备份（可选，留空则跳过）
REMOTE_BACKUP_HOST="${REMOTE_BACKUP_HOST:-}"
REMOTE_BACKUP_DIR="${REMOTE_BACKUP_DIR:-/guju/mindsafe/backups}"

# ===== 初始化 =====
mkdir -p "${STAGING_DIR}/daily" "${STAGING_DIR}/weekly" "${STAGING_DIR}/monthly" "${LOG_DIR}"

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
DAY_OF_WEEK=$(date +%u)  # 1=Monday, 7=Sunday
DAY_OF_MONTH=$(date +%d)

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

# ===== 执行备份（直写 dbbackups volume，与自动备份容器同链路） =====
BACKUP_NAME="mindsafe_${TIMESTAMP}.dump"
log "开始备份: ${DB_NAME} → volume ${BACKUP_VOLUME}:/daily/${BACKUP_NAME}"

docker exec "${CONTAINER_NAME}" pg_dump \
    -U "${DB_USER}" \
    -d "${DB_NAME}" \
    --format=custom \
    --compress=9 \
    --verbose \
    | tee "${STAGING_DIR}/daily/${BACKUP_NAME}" \
    | docker run --rm -i -v "${BACKUP_VOLUME}:/backups" -w /backups \
        pgvector/pgvector:pg16 sh -c 'mkdir -p daily && cat > daily/'"${BACKUP_NAME}"

# 校验暂存副本非空
if [ ! -s "${STAGING_DIR}/daily/${BACKUP_NAME}" ]; then
    log "ERROR: 备份文件为空，备份失败！"
    exit 1
fi

FILESIZE=$(du -h "${STAGING_DIR}/daily/${BACKUP_NAME}" | cut -f1)
log "备份完成: ${BACKUP_NAME} (${FILESIZE})"

# 校验备份可恢复性（列出 TOC）
if docker run --rm -v "${BACKUP_VOLUME}:/backups" -w /backups \
        pgvector/pgvector:pg16 pg_restore --list "daily/${BACKUP_NAME}" > /dev/null 2>&1; then
    log "备份完整性校验通过"
else
    log "WARNING: 备份完整性校验失败，文件可能损坏"
fi

# ===== 周备份（每周日） =====
if [ "${DAY_OF_WEEK}" = "7" ]; then
    cp "${STAGING_DIR}/daily/${BACKUP_NAME}" "${STAGING_DIR}/weekly/mindsafe_week_${TIMESTAMP}.dump"
    docker run --rm -v "${BACKUP_VOLUME}:/backups" -w /backups pgvector/pgvector:pg16 \
        sh -c 'mkdir -p weekly && cp daily/'"${BACKUP_NAME}"' weekly/mindsafe_week_'"${TIMESTAMP}"'.dump'
    log "周备份: mindsafe_week_${TIMESTAMP}.dump"
fi

# ===== 月备份（每月 1 日） =====
if [ "${DAY_OF_MONTH}" = "01" ]; then
    cp "${STAGING_DIR}/daily/${BACKUP_NAME}" "${STAGING_DIR}/monthly/mindsafe_month_${TIMESTAMP}.dump"
    docker run --rm -v "${BACKUP_VOLUME}:/backups" -w /backups pgvector/pgvector:pg16 \
        sh -c 'mkdir -p monthly && cp daily/'"${BACKUP_NAME}"' monthly/mindsafe_month_'"${TIMESTAMP}"'.dump'
    log "月备份: mindsafe_month_${TIMESTAMP}.dump"
fi

# ===== 清理过期备份（volume + 暂存双清） =====
log "清理过期备份..."
docker run --rm -v "${BACKUP_VOLUME}:/backups" -w /backups pgvector/pgvector:pg16 sh -c \
    'find daily -name "mindsafe_*.dump" -mtime +'"${RETAIN_DAILY}"' -delete 2>/dev/null || true;
     find weekly -name "mindsafe_week_*.dump" -mtime +'"$((RETAIN_WEEKLY * 7))"' -delete 2>/dev/null || true;
     find monthly -name "mindsafe_month_*.dump" -mtime +'"$((RETAIN_MONTHLY * 30))"' -delete 2>/dev/null || true'
find "${STAGING_DIR}/daily" -name "mindsafe_*.dump" -mtime +${RETAIN_DAILY} -delete
find "${STAGING_DIR}/weekly" -name "mindsafe_week_*.dump" -mtime +$((RETAIN_WEEKLY * 7)) -delete
find "${STAGING_DIR}/monthly" -name "mindsafe_month_*.dump" -mtime +$((RETAIN_MONTHLY * 30)) -delete

# ===== 异地同步（可选） =====
if [ -n "${REMOTE_BACKUP_HOST}" ]; then
    log "同步到异地: ${REMOTE_BACKUP_HOST}"
    rsync -az --delete "${STAGING_DIR}/" "${REMOTE_BACKUP_HOST}:${REMOTE_BACKUP_DIR}/"
    log "异地同步完成"
fi

# ===== 统计 =====
TOTAL_BACKUPS=$(find "${STAGING_DIR}" -name "*.dump" | wc -l)
TOTAL_SIZE=$(du -sh "${STAGING_DIR}" | cut -f1)
log "当前备份统计: ${TOTAL_BACKUPS} 个文件, 共 ${TOTAL_SIZE}"
log "===== 备份任务结束 ====="
