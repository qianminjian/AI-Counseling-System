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
# 部署根路径与 deploy.sh 的 REMOTE_DIR 同源（DA-12：一致性由 tests/unit/scripts/verify-remote-dir-test.sh 兜底）
REMOTE_DIR="/guju/mindsafe"
# compose 文件位于仓库 deploy/ 目录，服务器上镜像仓库结构（见 deploy.sh）
COMPOSE_DIR="${REMOTE_DIR}/deploy"
HEALTH_MAX_RETRIES=3
HEALTH_POLL_INTERVAL=5
HEALTH_MAX_POLLS=12  # 每个服务最多等 60s

# ===== .env 读取单点（D4：收敛散落 grep；仅按需加载密钥变量，其他变量不暴露给本脚本环境） =====
# 2026-08-06 切换后：生产 .env 位于 $COMPOSE_DIR/.env（老套 /guju/mindsafe/.env 已停用）
ENV_FILE="$COMPOSE_DIR/.env"
load_env_var() {
  # $1 = 变量名；echo 变量值（文件缺失或未定义时输出空）
  [ -f "$ENV_FILE" ] || { echo ""; return 0; }
  grep "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2- || true
}
export REDIS_PASSWORD
REDIS_PASSWORD="$(load_env_var REDIS_PASSWORD)"

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

# ⚠️ 注意：nginx 为【宿主 nginx】（/etc/nginx/nginx.conf，443 主入口）；compose nginx 服务已删除（DA-13 议决 a+b）
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
      # D5：消费 /health 的 DEGRADED 语义（降级 ≠ 宕机，不因引擎降级判服务不健康）
      #   cosyvoice-cloud=一级引擎可用；edge-tts/none=降级（告警但仍视为健康）
      local engine
      engine=$(docker exec "${CONTAINER_NAME[tts]}" python -c "
import json, urllib.request
try:
    data = json.load(urllib.request.urlopen('http://localhost:10096/health', timeout=5))
    print(data.get('engine', ''))
except Exception:
    raise SystemExit(2)  # 接口不可达/解析失败 → 非 0 退出（判不健康，与原语义一致）
" 2>/dev/null) || { log_warn "tts /health 读取失败（视为不健康）"; return 1; }
      case "$engine" in
        cosyvoice-cloud) return 0 ;;
        edge-tts) log_warn "TTS 降级运行（engine=edge-tts，云端 CosyVoice 不可用），请关注上游 API Key/配额"; return 0 ;;
        # OPS-007（doing/95）：engine=none 视为不健康——合成请求将 503 属业务中断而非降级，
        # 此前 return 0 导致部署回滚健康门禁与部署后 health 在 TTS 全灭时仍绿灯（告警兜底但无门禁）
        none)     log_warn "TTS 引擎全部不可用（engine=none），合成请求将 503——判定不健康（部署门禁红灯）"; return 1 ;;
        *)        log_warn "tts /health engine 未知（engine=${engine:-空}）——请 人工检查 TTS 状态"; return 0 ;;
      esac
      ;;
    voice)
      # DA-02：消费 /health 的 status 语义（与 tts 分支 D5 同构：降级 ≠ 宕机）
      #   UP=健康；DEGRADED=告警但仍健康（SER 未就绪，情绪识别降级为中性）；
      #   DOWN=不健康（ASR 核心链路不可用）
      local vstatus
      vstatus=$(docker exec "${CONTAINER_NAME[voice]}" python -c "
import json, urllib.request
try:
    data = json.load(urllib.request.urlopen('http://localhost:10095/health', timeout=5))
    print(data.get('status', ''))
except Exception:
    raise SystemExit(2)  # 接口不可达/解析失败 → 非 0 退出（判不健康，与原语义一致）
" 2>/dev/null) || { log_warn "voice /health 读取失败（视为不健康）"; return 1; }
      case "$vstatus" in
        UP)       return 0 ;;
        DEGRADED) log_warn "语音服务降级运行（status=DEGRADED，SER 未就绪，情绪识别降级为中性）——请检查模型加载日志"; return 0 ;;
        DOWN)     log_warn "语音服务不可用（status=DOWN，ASR 未就绪）——请检查 ASR 引擎配置"; return 1 ;;
        *)        log_warn "voice /health status 未知（status=${vstatus:-空}）——请人工检查语音服务状态"; return 0 ;;
      esac
      ;;
    backend)
      # backend 无宿主机端口映射，进容器内检查
      # -T 5（2026-08-29）：旧命令无超时——容器内端口假死时 wget 永久阻塞健康检查线程；
      # tts/voice 分支的 python urllib 均有 timeout=5，此处对齐
      docker exec "${CONTAINER_NAME[backend]}" wget -qO- -T 5 http://localhost:8080/actuator/health >/dev/null 2>&1
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

  # nginx 特判（2026-08-07 修复，DOC-063 实测）：宿主 nginx 为 443 主入口，
  # compose nginx 服务已删除（DA-13 议决 a+b）；不执行 compose up（避免 443 bind 冲突），
  # 直接健康探测宿主 443
  if [ "$svc" = "nginx" ]; then
    if wait_healthy nginx; then
      log_info "nginx 健康检查通过 ✓（宿主 nginx）"
      return 0
    fi
    log_error "nginx 健康检查失败（宿主 nginx 443 未监听或站点异常）"
    return 1
  fi

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

# AUD-034：宿主 nginx 直接 alias 前端口/目录（$REMOTE_DIR/frontend/*/dist，compose nginx 已删，DA-13），
# 目录缺失时前端直接 404 无 fail-fast；启动 nginx 前显式检查
check_frontend_dist() {
  local missing=""
  for app in student-h5 teacher-web parent-h5; do
    if [ ! -d "${REMOTE_DIR}/frontend/$app/dist" ]; then
      missing="$missing $app"
    fi
  done
  if [ -n "$missing" ]; then
    log_error "前端 dist 缺失（${missing}）——nginx 会静默挂载空目录导致前端空白"
    log_error "请先发布前端：本地执行 ./deploy.sh（DOC-063：唯一发布通道，CD 已取消）"
    return 1
  fi
  return 0
}

# ===== 命令实现 =====
cmd_start() {
  local services
  services=$(parse_services "${START_ORDER[@]}")
  local failed=0

  # AUD-034：目标含 nginx 时先校验前端 dist（缺失即 fail-fast，不启动）
  if echo "$services" | grep -q 'nginx'; then
    if ! check_frontend_dist; then
      return 1
    fi
  fi

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

  # AUD-034：重启目标含 nginx 时先校验前端 dist（缺失即 fail-fast，不重启）
  if echo "$services_start" | grep -q 'nginx'; then
    if ! check_frontend_dist; then
      return 1
    fi
  fi

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
