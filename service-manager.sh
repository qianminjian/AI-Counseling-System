#!/bin/bash
# MindSafe 服务启停管理器（在服务器上执行）
# 用法：
#   ./service-manager.sh start [service...]    按依赖顺序启动（默认全部）
#   ./service-manager.sh stop [service...]     按依赖逆序停止（默认全部）
#   ./service-manager.sh restart [service...]  重启（stop + start）
#   ./service-manager.sh status                查看所有服务状态
#   ./service-manager.sh health [service...]   仅执行健康检查
#
# 可选 service: postgres redis tts voice backend nginx
# 不指定则操作全部服务
#
# 特性：
#   - 启动按依赖拓扑排序：postgres/redis → tts/voice → backend → nginx
#   - 停止按逆序：nginx → backend → tts/voice → redis → postgres
#   - 启动后自动健康检查，失败自动重试（最多 3 次）
#   - 停止后检查进程残留，自动 kill
#   - 退出码：0=全部成功 1=有失败
set -eo pipefail

# ===== 配置 =====
# compose 文件位于仓库 deploy/ 目录，服务器上镜像仓库结构（见 deploy.sh）
COMPOSE_DIR="/guju/mindsafe/deploy"
HEALTH_MAX_RETRIES=3
HEALTH_POLL_INTERVAL=5
HEALTH_MAX_POLLS=12  # 每个服务最多等 60s

# 健康检查需要的密钥变量（仅按需加载 REDIS_PASSWORD，其他变量不暴露给本脚本环境）
# 2026-08-06 切换后：生产 .env 位于 $COMPOSE_DIR/deploy/.env（老套 /guju/mindsafe/.env 已停用）
if [ -f /guju/mindsafe/deploy/.env ]; then
  REDIS_PASSWORD=$(grep '^REDIS_PASSWORD=' /guju/mindsafe/deploy/.env | head -1 | cut -d= -f2-)
  export REDIS_PASSWORD
fi

# 颜色（终端友好）
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }

# ===== 服务定义 =====
# 启动顺序（数组下标 = 依赖层级）
START_ORDER=(postgres redis tts voice backend nginx)
# 停止顺序（逆序）
STOP_ORDER=(nginx backend voice tts redis postgres)

# Docker compose 服务名映射（与 deploy/docker-compose.prod.yml 服务键一致）
declare -A COMPOSE_NAME=(
  [postgres]="postgres"
  [redis]="redis"
  [tts]="tts-service"
  [voice]="voice-service"
  [backend]="backend"
  [nginx]="nginx"
)

# ⚠️ 注意：nginx 为【宿主 nginx】（/etc/nginx/nginx.conf，443 主入口），compose 的 nginx 服务未启用（容器 Created）
#   service-manager 的 nginx 健康检查（curl 127.0.0.1）实际探测的是宿主 nginx（2026-08-06 切换教训）

# 容器名映射（与 compose container_name 一致，用于 status/残留检查）
declare -A CONTAINER_NAME=(
  [postgres]="mindsafe-pg"
  [redis]="mindsafe-redis"
  [tts]="mindsafe-tts"
  [voice]="mindsafe-voice"
  [backend]="mindsafe-backend"
  [nginx]="mindsafe-nginx"
)

# ===== 健康检查函数 =====
check_health() {
  local svc="$1"
  case "$svc" in
    postgres)
      docker exec "${CONTAINER_NAME[postgres]}" pg_isready -U mindsafe >/dev/null 2>&1
      ;;
    redis)
      # redis 设了 requirepass，必须带密码才能 ping
      docker exec -e REDIS_PASSWORD="$REDIS_PASSWORD" "${CONTAINER_NAME[redis]}" redis-cli -a "$REDIS_PASSWORD" --no-auth-warning ping 2>/dev/null | grep -q PONG
      ;;
    tts)
      docker exec "${CONTAINER_NAME[tts]}" python -c "import urllib.request; urllib.request.urlopen('http://localhost:10096/health')" >/dev/null 2>&1
      ;;
    voice)
      docker exec "${CONTAINER_NAME[voice]}" python -c "import urllib.request; urllib.request.urlopen('http://localhost:10095/health')" >/dev/null 2>&1
      ;;
    backend)
      # backend 无宿主机端口映射，进容器内检查
      docker exec "${CONTAINER_NAME[backend]}" wget -qO- http://localhost:8080/actuator/health >/dev/null 2>&1
      ;;
    nginx)
      # 宿主 nginx：443 主入口探测（2026-08-06 修复：curl 必须带 Host 头，否则落默认 server 返回假 502）
      curl -sfk -o /dev/null -H 'Host: yun.gxjugu.com' https://127.0.0.1/mindsafe/ 2>/dev/null
      ;;
    *)
      return 1
      ;;
  esac
}

# 轮询等待健康
wait_healthy() {
  local svc="$1"
  local max_polls="${2:-$HEALTH_MAX_POLLS}"
  local i=1
  while [ $i -le "$max_polls" ]; do
    if check_health "$svc"; then
      return 0
    fi
    sleep "$HEALTH_POLL_INTERVAL"
    i=$((i + 1))
  done
  return 1
}

# ===== 启动单个服务 =====
start_service() {
  local svc="$1"

  # 已运行则跳过
  local container="${CONTAINER_NAME[$svc]}"
  local state
  state=$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null || echo "not_found")
  if [ "$state" = "running" ]; then
    log_info "$svc 已在运行，跳过启动"
    return 0
  fi

  log_info "启动 $svc ..."

  local compose_svc="${COMPOSE_NAME[$svc]}"
  cd "$COMPOSE_DIR"
  docker compose -f docker-compose.prod.yml up -d "$compose_svc" 2>&1 | tail -3

  # 健康检查 + 自动补偿
  local attempt=1
  while [ $attempt -le $HEALTH_MAX_RETRIES ]; do
    if wait_healthy "$svc"; then
      log_info "$svc 健康检查通过 ✓"
      return 0
    fi
    log_warn "$svc 健康检查失败（第 $attempt/$HEALTH_MAX_RETRIES 次），尝试重启..."
    # 补偿：重启容器
    cd "$COMPOSE_DIR"
    docker compose -f docker-compose.prod.yml restart "$compose_svc" 2>/dev/null
    attempt=$((attempt + 1))
  done

  log_error "$svc 启动失败（$HEALTH_MAX_RETRIES 次重试后仍不健康）"
  return 1
}

# ===== 停止单个服务 =====
stop_service() {
  local svc="$1"

  local container="${CONTAINER_NAME[$svc]}"
  local state
  state=$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null || echo "not_found")

  if [ "$state" = "not_found" ] || [ "$state" = "exited" ]; then
    log_info "$svc 未运行，跳过"
    return 0
  fi

  log_info "停止 $svc ..."
  local compose_svc="${COMPOSE_NAME[$svc]}"
  cd "$COMPOSE_DIR"
  docker compose -f docker-compose.prod.yml stop "$compose_svc" 2>/dev/null

  # 残留检查：容器仍在运行
  sleep 2
  state=$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null || echo "not_found")
  if [ "$state" = "running" ]; then
    log_warn "$svc 容器残留（仍在 running），强制 kill..."
    docker kill "$container" 2>/dev/null || true
    sleep 2
    state=$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null || echo "not_found")
    if [ "$state" = "running" ]; then
      log_error "$svc 无法停止"
      return 1
    fi
  fi
  log_info "$svc 已停止 ✓"
  return 0
}

# ===== 解析目标服务列表 =====
parse_services() {
  local order=("$@")
  if [ ${#TARGET_SERVICES[@]} -eq 0 ]; then
    # 未指定 → 全部
    echo "${order[@]}"
  else
    # 按 order 中的顺序过滤
    local result=()
    for svc in "${order[@]}"; do
      for t in "${TARGET_SERVICES[@]}"; do
        if [ "$svc" = "$t" ]; then
          result+=("$svc")
          break
        fi
      done
    done
    echo "${result[@]}"
  fi
}

# ===== 命令实现 =====
cmd_start() {
  local services
  services=$(parse_services "${START_ORDER[@]}")
  local failed=0

  log_info "=== 启动服务（依赖顺序）==="
  for svc in $services; do
    if ! start_service "$svc"; then
      failed=1
      log_error "服务 $svc 启动失败，中止后续启动"
      break
    fi
  done

  if [ $failed -eq 0 ]; then
    log_info "=== 全部服务启动成功 ==="
  fi
  return $failed
}

cmd_stop() {
  local services
  services=$(parse_services "${STOP_ORDER[@]}")
  local failed=0

  log_info "=== 停止服务（逆依赖顺序）==="
  for svc in $services; do
    if ! stop_service "$svc"; then
      failed=1
      log_warn "服务 $svc 停止异常，继续处理其余服务..."
    fi
  done

  if [ $failed -eq 0 ]; then
    log_info "=== 全部服务已停止 ==="
  fi
  return $failed
}

cmd_restart() {
  local services_stop
  services_stop=$(parse_services "${STOP_ORDER[@]}")
  local services_start
  services_start=$(parse_services "${START_ORDER[@]}")

  log_info "=== 重启服务 ==="
  for svc in $services_stop; do
    stop_service "$svc" || true
  done
  sleep 2
  local failed=0
  for svc in $services_start; do
    if ! start_service "$svc"; then
      failed=1
      break
    fi
  done
  return $failed
}

cmd_status() {
  echo ""
  printf "%-12s %-28s %-12s %-8s\n" "SERVICE" "CONTAINER" "STATE" "HEALTH"
  printf "%-12s %-28s %-12s %-8s\n" "-------" "---------" "-----" "------"

  for svc in "${START_ORDER[@]}"; do
    local container="${CONTAINER_NAME[$svc]}"
    local state
    state=$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null || echo "not_found")
    local health="✗"
    if [ "$state" = "running" ] && check_health "$svc"; then
      health="✓"
    fi
    printf "%-12s %-28s %-12s %-8s\n" "$svc" "$container" "$state" "$health"
  done
  echo ""
}

cmd_health() {
  local services
  services=$(parse_services "${START_ORDER[@]}")
  local all_ok=true

  log_info "=== 健康检查 ==="
  for svc in $services; do
    if check_health "$svc"; then
      log_info "$svc: 健康 ✓"
    else
      log_error "$svc: 不健康 ✗"
      all_ok=false
    fi
  done

  if $all_ok; then
    log_info "=== 全部健康 ==="
    return 0
  else
    log_error "=== 存在不健康服务 ==="
    return 1
  fi
}

# ===== 主入口 =====
ACTION="${1:-}"
shift 2>/dev/null || true
TARGET_SERVICES=("$@")

case "$ACTION" in
  start)   cmd_start ;;
  stop)    cmd_stop ;;
  restart) cmd_restart ;;
  status)  cmd_status ;;
  health)  cmd_health ;;
  *)
    echo "用法: $0 {start|stop|restart|status|health} [service...]"
    echo "服务: postgres redis tts voice backend nginx"
    echo "示例:"
    echo "  $0 start              # 启动全部"
    echo "  $0 restart backend    # 仅重启后端"
    echo "  $0 stop nginx backend # 停止 nginx 和后端"
    echo "  $0 status             # 查看状态"
    exit 1
    ;;
esac
