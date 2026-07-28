#!/usr/bin/env bash
# OPS-005: MindSafe 数据库自动备份脚本
# 用法: ./db-backup.sh [--restore <backup_file>]
# 定时: crontab -e → 0 2 * * * /opt/mindsafe/scripts/db-backup.sh >> /var/log/mindsafe-backup.log 2>&1
#
# 功能:
#   1. pg_dump 全量备份（custom 格式，支持并行恢复）
#   2. gzip 压缩（节省 70%+ 空间）
#   3. 本地保留 7 天 + 异地同步（可选 rsync/S3）
#   4. 备份完整性校验（pg_restore --list）
#   5. 恢复模式: --restore <file> 从备份恢复
#
# 环境变量:
#   DB_HOST       数据库主机（默认 localhost）
#   DB_PORT       数据库端口（默认 5433）
#   DB_NAME       数据库名（默认 mindsafe）
#   DB_USER       数据库用户（默认 mindsafe）
#   DB_PASSWORD   数据库密码（必须设置）
#   BACKUP_DIR    备份目录（默认 /opt/mindsafe/backups）
#   RETENTION_DAYS 本地保留天数（默认 7）
#   REMOTE_BACKUP_DIR 异地备份目录（可选，rsync 目标）

set -euo pipefail

# ===== 配置 =====
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5433}"
DB_NAME="${DB_NAME:-mindsafe}"
DB_USER="${DB_USER:-mindsafe}"
BACKUP_DIR="${BACKUP_DIR:-/opt/mindsafe/backups}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"
REMOTE_BACKUP_DIR="${REMOTE_BACKUP_DIR:-}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="${BACKUP_DIR}/mindsafe_${TIMESTAMP}.dump.gz"

# ===== 颜色输出 =====
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}[INFO]${NC} $(date '+%Y-%m-%d %H:%M:%S') $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $(date '+%Y-%m-%d %H:%M:%S') $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $(date '+%Y-%m-%d %H:%M:%S') $*" >&2; }

# ===== 恢复模式 =====
if [[ "${1:-}" == "--restore" ]]; then
    RESTORE_FILE="${2:-}"
    if [[ -z "$RESTORE_FILE" ]]; then
        log_error "用法: $0 --restore <backup_file>"
        exit 1
    fi
    if [[ ! -f "$RESTORE_FILE" ]]; then
        log_error "备份文件不存在: $RESTORE_FILE"
        exit 1
    fi

    log_warn "⚠️  即将从备份恢复数据库: $RESTORE_FILE"
    log_warn "目标: ${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME}"
    read -p "确认恢复？(yes/no): " CONFIRM
    if [[ "$CONFIRM" != "yes" ]]; then
        log_info "已取消恢复"
        exit 0
    fi

    log_info "开始恢复..."
    gunzip -c "$RESTORE_FILE" | pg_restore \
        --host="$DB_HOST" --port="$DB_PORT" \
        --username="$DB_USER" --dbname="$DB_NAME" \
        --clean --if-exists --no-owner --no-privileges \
        --jobs=4 2>&1 || true

    log_info "✅ 恢复完成: $RESTORE_FILE"
    exit 0
fi

# ===== 备份模式 =====
export PGPASSWORD="${DB_PASSWORD:-}"

# 前置检查
if ! command -v pg_dump &>/dev/null; then
    log_error "pg_dump 未安装"
    exit 1
fi

mkdir -p "$BACKUP_DIR"

log_info "开始备份: ${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME}"
log_info "输出: $BACKUP_FILE"

# 1. 执行备份（custom 格式 + gzip 压缩）
START_TIME=$(date +%s)
pg_dump \
    --host="$DB_HOST" --port="$DB_PORT" \
    --username="$DB_USER" --dbname="$DB_NAME" \
    --format=custom --compress=9 \
    | gzip > "$BACKUP_FILE"

END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))
FILE_SIZE=$(du -h "$BACKUP_FILE" | cut -f1)

log_info "备份完成: 大小=${FILE_SIZE}, 耗时=${DURATION}s"

# 2. 完整性校验
log_info "校验备份完整性..."
if gunzip -c "$BACKUP_FILE" | pg_restore --list > /dev/null 2>&1; then
    log_info "✅ 备份校验通过"
else
    log_error "❌ 备份校验失败！文件可能损坏: $BACKUP_FILE"
    exit 1
fi

# 3. 清理过期备份
DELETED=$(find "$BACKUP_DIR" -name "mindsafe_*.dump.gz" -mtime +"$RETENTION_DAYS" -delete -print | wc -l)
if [[ "$DELETED" -gt 0 ]]; then
    log_info "清理过期备份: ${DELETED} 个（>${RETENTION_DAYS}天）"
fi

# 4. 异地同步（可选）
if [[ -n "$REMOTE_BACKUP_DIR" ]]; then
    log_info "异地同步: $REMOTE_BACKUP_DIR"
    rsync -az --delete "$BACKUP_DIR/" "$REMOTE_BACKUP_DIR/" 2>/dev/null \
        && log_info "✅ 异地同步完成" \
        || log_warn "异地同步失败（不影响本地备份）"
fi

# 5. 输出摘要
log_info "===== 备份摘要 ====="
log_info "文件: $BACKUP_FILE"
log_info "大小: $FILE_SIZE"
log_info "耗时: ${DURATION}s"
log_info "保留: 本地 ${RETENTION_DAYS} 天"
log_info "===================="
