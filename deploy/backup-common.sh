#!/usr/bin/env bash
# deploy/backup-common.sh —— DB 备份/恢复共享事实（单一事实源，DC-002）
# 被 backup.sh / restore.sh source 使用，不单独执行。
#
# 收敛内容（此前 backup.sh / restore.sh 各自硬编码一份，卷名靠正则猜测）：
#   1. DB 连接事实（容器名/库名/用户名，与 docker-compose*.yml 的 postgres 服务对齐）
#   2. dbbackups 卷探测（环境变量 BACKUP_VOLUME 优先，否则自动探测 *_dbbackups 卷）
#   3. 统一时间戳日志函数 log()
# 部署位置：服务器 /guju/mindsafe/deploy/backup-common.sh（setup-server.sh 随 backup.sh 一并复制）

# ===== DB 连接事实（唯一定义处） =====
CONTAINER_NAME="mindsafe-pg"
DB_NAME="mindsafe"
DB_USER="mindsafe"
# 备份/恢复容器镜像
PG_IMAGE="${PG_IMAGE:-pgvector/pgvector:pg16}"

# ===== dbbackups 卷探测 =====
# compose 命名卷实际带项目名前缀（如 deploy_dbbackups），硬编码 dbbackups 会挂到另一个空卷。
# 优先用环境变量 BACKUP_VOLUME 覆盖，否则自动探测宿主机上匹配 *_dbbackups 的卷；
# 探测为空则幂等自愈创建（OPS-P0-01，doing/96：compose 已声明 dbbackups 卷，此处兜底老环境），
# 创建后仍为空即 fail-fast（避免备份/恢复静默写到错误的空卷）。
detect_backup_volume() {
    BACKUP_VOLUME="${BACKUP_VOLUME:-$(docker volume ls --format '{{.Name}}' | grep -E '(^|_)dbbackups$' | head -1)}"
    if [ -z "${BACKUP_VOLUME}" ]; then
        # 幂等自愈：按 compose 默认项目名（compose 文件所在目录名）创建卷，
        # 与 docker compose up 自动创建的命名卷同名，老环境无需手工补卷。
        PROJECT_NAME="${COMPOSE_PROJECT_NAME:-$(basename "$(dirname "${BASH_SOURCE[0]}")")}"
        log "未探测到 dbbackups 卷，幂等自愈创建 ${PROJECT_NAME}_dbbackups..."
        docker volume create "${PROJECT_NAME}_dbbackups" >/dev/null 2>&1 || {
            echo "ERROR: 自愈创建 ${PROJECT_NAME}_dbbackups 失败（请 docker compose up -d 或设 BACKUP_VOLUME=<卷名>）" >&2
            exit 1
        }
        BACKUP_VOLUME="${PROJECT_NAME}_dbbackups"
        log "已创建备份卷: ${BACKUP_VOLUME}"
    fi
}

# ===== 统一日志 =====
log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

# source 时即执行探测（backup/restore 启动即需要卷，尽早 fail-fast）
detect_backup_volume
