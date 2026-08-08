#!/bin/bash
# MindSafe 手动部署脚本（本地执行）——**唯一发布通道**
# ⚠️ 发布通道决策（DOC-063，2026-08-07）：**取消 GitHub CD，部署统一走真实环境**
#    （本脚本）：git push main → CI 全绿（质量门禁）→ 本地执行本脚本 → rsync 增量同步源码
#    → 服务器 compose 本地构建 → service-manager 重启 + 健康检查（见 DEPLOY-GUIDE.md §二）。
#    背景：CD 镜像 pull 模型在 3Mbps 带宽下首次全量 36min+ 且 GHCR 抖动整次失败（doing/72 §2）。
# 用法：
#   ./deploy.sh              自动检测变更组件，只部署受影响的服务
#   ./deploy.sh --all        强制全量部署
#   ./deploy.sh --force      强制重新部署（跳过变更检测，等同于 --all）
#   ./deploy.sh --backend    强制部署后端
#   ./deploy.sh --student    强制部署学生端
#   ./deploy.sh --teacher    强制部署教师端
#   ./deploy.sh --parent     强制部署家长端
#   ./deploy.sh --tts        强制部署 TTS 服务
#   ./deploy.sh --voice      强制部署 Voice 服务（ASR+SER）
#   ./deploy.sh --rollback backend   回滚后端到上一版本
#   SKIP_SMOKE=1 ./deploy.sh  跳过发布后置冒烟（DA-04 逃生口，仅后端部署时生效）
# 前置条件：已 commit + push，CI 通过（与 CD 相同的门禁）
set -eo pipefail

# macOS openrsync (/usr/bin/rsync) 仅实现 rsync 协议 29 子集，与服务器 rsync 3.x 不兼容；
# 传输含 WASM/workbox/.gitkeep 的 dist 时 sender 进程会崩 (exit 11 / SIGSEGV)。
# Homebrew rsync 是真 rsync 3.x，前置 PATH 即可。Linux 上无 /opt/homebrew/bin/rsync 不受影响。
if [ -x /opt/homebrew/bin/rsync ]; then
  export PATH="/opt/homebrew/bin:$PATH"
fi

# 部署目标通过环境变量指定（不在仓库内硬编码生产 IP）
# 建议写入 ~/.zshrc：export MINDSAFE_SERVER=user@<服务器IP>
SERVER="${MINDSAFE_SERVER:-}"
# SSH 长命令防断连挂起（2026-08-07 部署教训：远程 build/restart 无保活时，
# 本机网络抖动断开会话 → 本地 ssh 挂起等待、远程构建进程被 SIGHUP 中断；
# 30s 心跳 × 60 次 = 30 分钟窗口，足够覆盖后端 Maven 打包）
SSH_OPTS=(-o ConnectTimeout=15 -o ServerAliveInterval=30 -o ServerAliveCountMax=60)
if [ -z "${SERVER}" ]; then
  echo "ERROR: 必须设置 MINDSAFE_SERVER（如 export MINDSAFE_SERVER=mindsafe@<服务器IP>）"
  exit 1
fi
REMOTE_DIR="/guju/mindsafe"
PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
STATE_FILE="$PROJECT_ROOT/.deploy-state"
# 服务器上 compose 位于 $REMOTE_DIR/deploy/，挂载/构建上下文为仓库镜像结构：
#   ../frontend/<app>/dist、../backend/<svc>（compose build context）
# ⚠️ 前端静态文件由【宿主 nginx 直接 alias】（/etc/nginx/nginx.conf，443 主入口）；
#   compose 的 nginx 服务未启用（容器 Created），勿改其配置期望生效（2026-08-06 切换教训）
# 与 service-manager.sh 的 COMPOSE_DIR 保持一致

# ===== 参数解析 =====
FORCE_ALL=false
FORCE_BACKEND=false
FORCE_STUDENT=false
FORCE_TEACHER=false
FORCE_PARENT=false
FORCE_TTS=false
FORCE_VOICE=false
ROLLBACK_TARGET=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --all|--force) FORCE_ALL=true; shift ;;
    --backend)  FORCE_BACKEND=true; shift ;;
    --student)  FORCE_STUDENT=true; shift ;;
    --teacher)  FORCE_TEACHER=true; shift ;;
    --parent)   FORCE_PARENT=true; shift ;;
    --tts)      FORCE_TTS=true; shift ;;
    --voice)    FORCE_VOICE=true; shift ;;
    --rollback) ROLLBACK_TARGET="${2:-backend}"; shift 2 ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac
done

# ===== 回滚模式 =====
# 说明：回滚 = 用服务器上已同步的源码重新构建镜像；
# 真正的版本回退请先 git revert + push 后再执行 ./deploy.sh
if [ -n "$ROLLBACK_TARGET" ]; then
  echo "⏪ 重建 $ROLLBACK_TARGET..."
  case "$ROLLBACK_TARGET" in
    backend)
      ssh "${SSH_OPTS[@]}" "$SERVER" "cd $REMOTE_DIR/deploy && docker compose -f docker-compose.prod.yml build backend && docker compose -f docker-compose.prod.yml up -d backend"
      echo "✅ 后端已重建"
      ;;
    tts)
      ssh "${SSH_OPTS[@]}" "$SERVER" "cd $REMOTE_DIR/deploy && docker compose -f docker-compose.prod.yml build tts-service && docker compose -f docker-compose.prod.yml up -d tts-service"
      echo "✅ TTS 服务已重建"
      ;;
    voice)
      ssh "${SSH_OPTS[@]}" "$SERVER" "cd $REMOTE_DIR/deploy && docker compose -f docker-compose.prod.yml build voice-service && docker compose -f docker-compose.prod.yml up -d voice-service"
      echo "✅ Voice 服务已重建"
      ;;
    *)
      echo "❌ 不支持回滚: ${ROLLBACK_TARGET}（支持 backend / tts / voice）"
      exit 1
      ;;
  esac
  exit 0
fi

# ===== 部署计时与监控 + 日志审计（DOC-077/078，doing/80/81） =====
# 库提供：步骤计时/历史基线/阈值判定/信号汇总/固定格式汇报/失败模式知识库
#        + 日志回归分析（R1-R6）/固定审计报告/主动修复（A1 轮转）
# shellcheck disable=SC1090
source "$PROJECT_ROOT/deploy/scripts/deploy-metrics.sh"
# shellcheck disable=SC1090
source "$PROJECT_ROOT/deploy/scripts/deploy-audit.sh"
LOG_DIR="$PROJECT_ROOT/logs/deploy"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/deploy-$(date +%Y%m%d-%H%M%S).log"
# 全量输出 tee 到日志（bash 3.2 进程替换；日志为基线数据源）
exec > >(tee -a "$LOG_FILE") 2>&1
# 部署流程标志：门禁拦截/参数错误（DM_IN_FLOW=0）不输出汇报/审计，保持快速失败
DM_IN_FLOW=0
# 部署结束统一出口（trap 链两段式，DOC-078）：
#   1) dm_finish_deploy：补 end 未完成步骤 → 汇报 → 统计段 → 快照
#   2) dm_audit_run：日志回归分析（R1-R6）→ A1 轮转 → 审计报告
#      （仅进入部署流程时审计；失败部署同样审计——失败是最需要分析的样本）
trap 'rc=$?; dm_finish_deploy "$rc" "${COMPONENTS:-}" "$STATE_FILE" "$LOG_DIR" "$LOG_FILE"; if [ "${DM_IN_FLOW:-0}" = "1" ]; then dm_audit_run "$LOG_DIR" "$LOG_FILE"; fi' EXIT

# ===== 前置检查 =====
dm_start precheck
echo "🔍 前置检查..."

if [ -n "$(git status --porcelain)" ]; then
  echo "❌ 有未提交的变更，请先 commit"
  git status --short
  exit 1
fi

git fetch origin main --quiet
LOCAL=$(git rev-parse HEAD)
REMOTE=$(git rev-parse origin/main)
if [ "$LOCAL" != "$REMOTE" ]; then
  echo "❌ 本地 HEAD 与 origin/main 不一致，请先 push"
  echo "   local:  $LOCAL"
  echo "   remote: $REMOTE"
  exit 1
fi
echo "✅ Git 状态正常"

# ===== Flyway 迁移脚本版本号唯一性校验 =====
MIGRATION_DIR="$PROJECT_ROOT/backend/counseling-app/src/main/resources/db/migration"
if [ -d "$MIGRATION_DIR" ]; then
  DUP_VERSIONS=$(ls "$MIGRATION_DIR"/V*.sql 2>/dev/null | sed 's/.*\/V\([0-9]*\)__.*/\1/' | sort -n | uniq -d)
  if [ -n "$DUP_VERSIONS" ]; then
    echo "❌ Flyway 迁移脚本版本号重复: $DUP_VERSIONS"
    echo "   请检查 $MIGRATION_DIR 下的 V*.sql 文件"
    exit 1
  fi
  echo "✅ Flyway 迁移脚本版本号无冲突"
fi
dm_end precheck

# ===== 变更检测 =====
dm_start detect
DEPLOY_BACKEND=$FORCE_BACKEND
DEPLOY_STUDENT=$FORCE_STUDENT
DEPLOY_TEACHER=$FORCE_TEACHER
DEPLOY_PARENT=$FORCE_PARENT
DEPLOY_TTS=$FORCE_TTS
DEPLOY_VOICE=$FORCE_VOICE

if $FORCE_ALL; then
  DEPLOY_BACKEND=true
  DEPLOY_STUDENT=true
  DEPLOY_TEACHER=true
  DEPLOY_PARENT=true
  DEPLOY_TTS=true
  DEPLOY_VOICE=true
elif ! $FORCE_BACKEND && ! $FORCE_STUDENT && ! $FORCE_TEACHER && ! $FORCE_PARENT && ! $FORCE_TTS && ! $FORCE_VOICE; then
  # 自动检测模式：基于 git diff
  if [ -f "$STATE_FILE" ]; then
    LAST_COMMIT=$(grep '^LAST_DEPLOYED_COMMIT=' "$STATE_FILE" | cut -d= -f2)
  fi

  if [ -z "$LAST_COMMIT" ] || ! git cat-file -e "$LAST_COMMIT" 2>/dev/null; then
    echo "ℹ️  无部署历史，执行全量部署"
    DEPLOY_BACKEND=true
    DEPLOY_STUDENT=true
    DEPLOY_TEACHER=true
    DEPLOY_PARENT=true
    DEPLOY_TTS=true
    DEPLOY_VOICE=true
  else
    echo "📋 检测变更（$LAST_COMMIT → HEAD）..."
    CHANGED=$(git diff --name-only "$LAST_COMMIT" HEAD)

    if [ -z "$CHANGED" ]; then
      dm_end detect
      echo "✅ 无变更，无需部署"
      exit 0
    fi

    # 路径映射
    echo "$CHANGED" | grep -q '^backend/tts-service/' && DEPLOY_TTS=true
    echo "$CHANGED" | grep -q '^backend/voice-service/' && DEPLOY_VOICE=true
    echo "$CHANGED" | grep '^backend/' | grep -v '^backend/tts-service/' | grep -qv '^backend/voice-service/' && DEPLOY_BACKEND=true
    echo "$CHANGED" | grep -q '^frontend/student-h5/' && DEPLOY_STUDENT=true
    echo "$CHANGED" | grep -q '^frontend/teacher-web/' && DEPLOY_TEACHER=true
    echo "$CHANGED" | grep -q '^frontend/parent-h5/' && DEPLOY_PARENT=true
    # deploy.sh / docker-compose 变更 → 全量
    echo "$CHANGED" | grep -qE '^(deploy\.sh|deploy/)' && {
      DEPLOY_BACKEND=true; DEPLOY_TTS=true; DEPLOY_VOICE=true
    }
  fi
fi

# 汇总
COMPONENTS=""
$DEPLOY_BACKEND && COMPONENTS="$COMPONENTS backend"
$DEPLOY_STUDENT && COMPONENTS="$COMPONENTS student"
$DEPLOY_TEACHER && COMPONENTS="$COMPONENTS teacher"
$DEPLOY_PARENT && COMPONENTS="$COMPONENTS parent"
$DEPLOY_TTS && COMPONENTS="$COMPONENTS tts"
$DEPLOY_VOICE && COMPONENTS="$COMPONENTS voice"

if [ -z "$COMPONENTS" ]; then
  dm_end detect
  echo "✅ 无需部署的组件变更"
  # 仍更新状态文件（可能有 design/ 等无需部署的变更）
  echo "LAST_DEPLOYED_COMMIT=$LOCAL" > "$STATE_FILE"
  echo "DEPLOYED_AT=$(date -Iseconds)" >> "$STATE_FILE"
  exit 0
fi

echo "🎯 待部署组件:$COMPONENTS"
dm_end detect
echo ""
# 进入部署流程：此后所有失败路径由 trap 统一输出汇报与统计段
DM_IN_FLOW=1

# ===== 选择性构建 =====
if $DEPLOY_BACKEND; then
  echo "📦 后端：本地预检编译（服务器 compose build 会重新打包）..."
  dm_start compile-backend
  cd "$PROJECT_ROOT/backend"
  mvn -q compile -DskipTests -pl counseling-app -am || { echo "❌ 后端编译失败，中止部署"; exit 1; }
  dm_end compile-backend
  echo "✅ 后端编译通过"
fi

if $DEPLOY_STUDENT; then
  # DA-06：构建前投放端侧模型（whisper-tiny + wespeaker ONNX），缺文件自动下载，失败即阻断
  echo "📦 端侧模型投放（deploy/scripts/prepare-models.sh）..."
  dm_start prepare-models
  bash "$PROJECT_ROOT/deploy/scripts/prepare-models.sh" || { echo "❌ 模型投放失败，中止部署"; exit 1; }
  dm_end prepare-models
  echo "📦 构建学生端..."
  dm_start build-student
  cd "$PROJECT_ROOT/frontend/student-h5"
  npm run build --silent
  dm_end build-student
  echo "✅ 学生端构建完成"
fi

if $DEPLOY_TEACHER; then
  echo "📦 构建教师端..."
  dm_start build-teacher
  cd "$PROJECT_ROOT/frontend/teacher-web"
  npm run build --silent
  dm_end build-teacher
  echo "✅ 教师端构建完成"
fi

if $DEPLOY_PARENT; then
  echo "📦 构建家长端..."
  dm_start build-parent
  cd "$PROJECT_ROOT/frontend/parent-h5"
  npm run build --silent
  dm_end build-parent
  echo "✅ 家长端构建完成"
fi

# ===== 带重试的统一执行器（D4：收敛 rsync/build/nginx 三处重试变体） =====
# 用法: retry <最大次数> <重试间隔秒> <命令...>
# 间隔传 0 时使用递增退避（1s, 2s, 3s...）；警告走 stderr（不污染命令 stdout 捕获）
retry() {
  local max_attempts="$1" delay="$2"
  shift 2
  local attempt=1
  while [ "$attempt" -le "$max_attempts" ]; do
    if "$@"; then
      return 0
    fi
    if [ "$attempt" -eq "$max_attempts" ]; then
      break
    fi
    local wait_secs="$delay"
    [ "$delay" -eq 0 ] && wait_secs="$attempt"
    echo "⚠️  第 ${attempt} 次失败，${wait_secs}s 后重试: $*" >&2
    sleep "$wait_secs"
    attempt=$((attempt + 1))
  done
  echo "❌ 重试 ${max_attempts} 次后仍失败: $*" >&2
  return 1
}

# ===== rsync 降速自愈（DOC-077 L2） =====
# 3Mbps 上行带宽下常规速率重传易失败（doing/72 教训）；常规 2 次失败后自动降速重试
rsync_deploy() {
  if retry 2 0 rsync "$@"; then
    return 0
  fi
  echo "⚠️ rsync 常规速率失败，自动降速 --bwlimit=4096 重试（L2 自愈）" >&2
  retry 2 0 rsync --bwlimit=4096 "$@"
}

# ===== 选择性上传（路径与 deploy/docker-compose.prod.yml 挂载/构建上下文对齐） =====
echo ""
echo "🚀 部署到服务器..."
dm_start rsync

if $DEPLOY_BACKEND; then
  # backend compose build context = ../backend（多阶段源码构建），排除构建产物与大文件
  rsync_deploy -avz --delete \
    --exclude 'target/' --exclude '.git/' \
    --exclude 'tts-service/wheels/' \
    "$PROJECT_ROOT/backend/" "$SERVER:$REMOTE_DIR/backend/"
  # 同步 backend 根 Dockerfile（context=backend）
fi

# ===== 端侧模型门禁（DA-06：design/04 §模型投放自动化 E-5 随 deploy.sh 通道生效） =====
# 此前 --verify 声称「CI 门禁用」却零自动消费方；且上传排除 models/ 导致 SAME_ORIGIN 404 静默失效。
# 现在：本地校验 fail-closed（缺模型阻断发布）+ 模型随 dist 上传（与 design/10 §模型下载一致）。
if $DEPLOY_STUDENT; then
  if ! bash "$PROJECT_ROOT/deploy/scripts/prepare-models.sh" --verify; then
    echo "❌ 端侧模型校验失败（前端 SAME_ORIGIN 依赖 dist/models，缺模型 → 唤醒/声纹 404）"
    echo "   首次投放请先执行: bash deploy/scripts/prepare-models.sh（下载 whisper-tiny + wespeaker ONNX）"
    exit 1
  fi
  if [ ! -d "$PROJECT_ROOT/frontend/student-h5/dist/models" ]; then
    echo "❌ dist 缺少 models/ —— 模型投放后需重新构建 student-h5（Vite 复制 public/ → dist/）"
    echo "   请执行: cd frontend/student-h5 && npm run build"
    exit 1
  fi
fi

$DEPLOY_STUDENT && rsync_deploy -avz --delete "$PROJECT_ROOT/frontend/student-h5/dist/" "$SERVER:$REMOTE_DIR/frontend/student-h5/dist/"
$DEPLOY_TEACHER && rsync_deploy -avz --delete "$PROJECT_ROOT/frontend/teacher-web/dist/" "$SERVER:$REMOTE_DIR/frontend/teacher-web/dist/"
$DEPLOY_PARENT && rsync_deploy -avz --delete "$PROJECT_ROOT/frontend/parent-h5/dist/" "$SERVER:$REMOTE_DIR/frontend/parent-h5/dist/"


if $DEPLOY_TTS; then
  # 审计 P2-24：wheels/ 为可再生的本地构建产物，不再随 rsync 上传（Dockerfile 已改在线安装）
  rsync_deploy -avz --delete --exclude 'wheels/' "$PROJECT_ROOT/backend/tts-service/" "$SERVER:$REMOTE_DIR/backend/tts-service/"
fi

if $DEPLOY_VOICE; then
  rsync_deploy -avz --delete "$PROJECT_ROOT/backend/voice-service/" "$SERVER:$REMOTE_DIR/backend/voice-service/"
fi

# 同步 deploy/ 目录（compose + nginx 配置 + 运维脚本），变更检测命中 deploy/ 时为全量部署
rsync_deploy -avz --exclude '.env' "$PROJECT_ROOT/deploy/" "$SERVER:$REMOTE_DIR/deploy/"

# ===== 上传 service-manager.sh（确保服务器有最新版本） =====
rsync_deploy -avz "$PROJECT_ROOT/service-manager.sh" "$SERVER:$REMOTE_DIR/service-manager.sh"
ssh "${SSH_OPTS[@]}" "$SERVER" "chmod +x $REMOTE_DIR/service-manager.sh"

# ===== 清理 CD 残留（DOC-063，2026-08-07） =====
# 取消 CD 后，服务器 .env 中可能残留 CD 写入的 *_IMAGE 变量（ghcr.io tag），
# 会污染 compose 默认 tag（mindsafe/*:local）语义；检测到即告警并幂等清理。
# 注意：必须先为运行中容器的镜像补默认 tag 再删行——否则 compose image 回退默认值后，
# 下次容器重建会尝试拉取 mindsafe/*:local（本地无此 tag → 失败）
ssh "${SSH_OPTS[@]}" "$SERVER" "cd $REMOTE_DIR/deploy && if grep -qE '^(BACKEND|VOICE_SERVICE|TTS_SERVICE)_IMAGE=' .env; then
  echo '⚠️ 检测到 CD 残留 IMAGE 变量，自动清理（DOC-063 决策）'
  for spec in 'mindsafe-backend backend' 'mindsafe-voice voice-service' 'mindsafe-tts tts-service'; do
    set -- \$spec
    img=\$(docker inspect --format '{{.Image}}' \$1 2>/dev/null) || true
    [ -n \"\$img\" ] && docker tag \$img mindsafe/\$2:local 2>/dev/null || true
  done
  sed -i -E '/^(BACKEND|VOICE_SERVICE|TTS_SERVICE)_IMAGE=/d' .env
  echo '✅ .env 已清理（compose 回退 mindsafe/*:local，运行镜像已补默认 tag）'
else
  echo '✅ .env 无 CD 残留'
fi"
dm_end rsync

# ===== 构建镜像（源码变更类组件需先 build，再经 service-manager 重启） =====
BUILD_TARGETS=""
$DEPLOY_BACKEND && BUILD_TARGETS="$BUILD_TARGETS backend"
$DEPLOY_TTS && BUILD_TARGETS="$BUILD_TARGETS tts-service"
$DEPLOY_VOICE && BUILD_TARGETS="$BUILD_TARGETS voice-service"

if [ -n "$BUILD_TARGETS" ]; then
  echo "🔨 构建镜像:$BUILD_TARGETS"
  dm_start build-images
  # build 重试 3 次、固定间隔 60s（对齐 CD pull 重试教训 doing/72 §2.2：服务器在线下载依赖同样受网络抖动影响）
  if ! retry 3 60 ssh "${SSH_OPTS[@]}" "$SERVER" "cd $REMOTE_DIR/deploy && docker compose -f docker-compose.prod.yml build $BUILD_TARGETS"; then
    # L3 自愈：清 builder 缓存后重试（幂等，无业务风险；2026-08-07 部署 Maven 408s 缓存膨胀教训）
    echo "⚠️ 镜像构建失败，清理 docker builder 缓存后重试（L3 自愈）"
    ssh "${SSH_OPTS[@]}" "$SERVER" "docker builder prune -f" >/dev/null 2>&1 || true
    if ! retry 2 60 ssh "${SSH_OPTS[@]}" "$SERVER" "cd $REMOTE_DIR/deploy && docker compose -f docker-compose.prod.yml build $BUILD_TARGETS"; then
      echo "❌ 镜像构建清理缓存后仍失败，部署中止（服务仍运行旧版本）"
      exit 1
    fi
  fi
  dm_end build-images
  echo "✅ 镜像构建完成"
fi

# ===== 选择性重启（通过 service-manager.sh 统一管理） =====
RESTART_TARGETS=""
$DEPLOY_BACKEND && RESTART_TARGETS="$RESTART_TARGETS backend"
$DEPLOY_TTS && RESTART_TARGETS="$RESTART_TARGETS tts"
$DEPLOY_VOICE && RESTART_TARGETS="$RESTART_TARGETS voice"
# 前端 dist 由【宿主 nginx】直接 alias 提供（非 compose nginx 容器挂载）；静态文件更新后需 reload 宿主 nginx 才生效
{ $DEPLOY_STUDENT || $DEPLOY_TEACHER || $DEPLOY_PARENT; } && RESTART_TARGETS="$RESTART_TARGETS nginx"

if [ -n "$RESTART_TARGETS" ]; then
  echo "🔄 重启服务:$RESTART_TARGETS"
  dm_start restart
  if ! ssh "${SSH_OPTS[@]}" "$SERVER" "cd $REMOTE_DIR && bash service-manager.sh restart $RESTART_TARGETS"; then
    echo "❌ 服务重启失败，请检查：ssh $SERVER 'cd $REMOTE_DIR && bash service-manager.sh status'"
    echo "   部署状态未更新，下次 deploy.sh 将重新部署"
    exit 1
  fi
  dm_end restart
  echo "✅ 服务重启 + 健康检查通过"
else
  echo "ℹ️  仅前端变更时也应重载 nginx（已纳入重启目标）"
fi

# ===== 发布后置冒烟（DA-04 议决：方案 B——deploy 现场 E2E 门禁，恢复 309909ed 冒烟能力） =====
# smoke-test.sh 31 断言（注册/SSE 对话/风险识别/TTS 音色/并发/稳定性）在服务器现场执行：
#   - 服务器具备完整真实环境（DB/真实 LLM/TTS），正是脚本设计用途（design/04 §部署后冒烟）
#   - CI 全栈 e2e 曾在 8983862a 因不可持续移除；API 契约层由 *IT.java + openapi 快照（BA-01）覆盖，不重复
#   - 后端宿主端口 18082（compose 映射），nginx 入口层已由 service-manager 健康检查覆盖
#   - 逃生口：SKIP_SMOKE=1 显式跳过（日志留痕）；失败门禁中止（部署状态不更新，下次重跑）
#   - 自动门禁跑默认路径断言（认证/注册/SSE 对话/风险/安全/TTS 音色/并发/稳定性）；
#     教师/管理员链路（需凭据）不在此执行——密码不进 ssh 命令行（ps 暴露），由后端 *IT.java 覆盖；
#     手动补充：ssh $SERVER 'cd $REMOTE_DIR && TEACHER_USER=x TEACHER_PASS=x ADMIN_USER=x ADMIN_PASS=x BASE_URL=http://localhost:18082 bash tests/e2e/smoke-test.sh'
if [ "$DEPLOY_BACKEND" = "true" ]; then
  if [ "${SKIP_SMOKE:-}" = "1" ]; then
    echo "ℹ️  SKIP_SMOKE=1 已设置，跳过发布后置冒烟"
  else
    echo "🧪 发布后置冒烟（tests/e2e/smoke-test.sh，默认路径断言）..."
    dm_start smoke
    ssh "${SSH_OPTS[@]}" "$SERVER" "mkdir -p $REMOTE_DIR/tests/e2e"
    if ! rsync_deploy -avz "$PROJECT_ROOT/tests/e2e/smoke-test.sh" "$SERVER:$REMOTE_DIR/tests/e2e/smoke-test.sh"; then
      echo "❌ smoke-test.sh 上传失败，部署中止"
      echo "   确认服务器可达后显式跳过重跑: SKIP_SMOKE=1 ./deploy.sh"
      exit 1
    fi
    if ! ssh "${SSH_OPTS[@]}" "$SERVER" "cd $REMOTE_DIR && BASE_URL=http://localhost:18082 bash tests/e2e/smoke-test.sh"; then
      echo "❌ 冒烟测试失败——后端已部署但业务链路未验证通过"
      echo "   排查: ssh $SERVER 'cd $REMOTE_DIR && BASE_URL=http://localhost:18082 bash tests/e2e/smoke-test.sh'"
      echo "   确认服务正常后显式跳过重跑: SKIP_SMOKE=1 ./deploy.sh"
      exit 1
    fi
    dm_end smoke
    echo "✅ 冒烟测试通过（断言数以上方脚本汇总为准）"
  fi
else
  echo "ℹ️  本次未部署后端，跳过冒烟测试"
fi

# ===== 更新部署状态（仅在部署成功后） =====
echo "LAST_DEPLOYED_COMMIT=$LOCAL" > "$STATE_FILE"
echo "DEPLOYED_AT=$(date -Iseconds)" >> "$STATE_FILE"

# ===== 前端路径校验（2026-08-06 切换教训固化）=====
# 宿主 nginx 静态 location 必须指向 deploy.sh 的部署目标目录（*-h5/dist），否则新构建不被服务
# 2026-08-07 修复（DOC-063 实测）:
# ① 变量一律 ${var} 包裹——bash 3.2（macOS 默认）在 UTF-8 locale 下会把 $var 后紧跟的
#    全角字符首字节并入变量名，导致变量展开为空 + 中文标点丢字节（部署日志乱码根因）
# ② 单次 ssh 会话完成全部校验 + 连接失败重试——本机网络（代理/运营商）会随机在 banner 阶段
#    关闭 ssh 连接（kex_exchange_identification: Connection closed），独立 3 次连接失败率远高于
#    1 次；旧实现 ssh 失败被 2>/dev/null 静默吞掉 → 误报"路径未对齐"（2026-08-07 parent 误报根因）
check_nginx_paths() {
  # $1 = 空格分隔的 app:path 列表（如 "student:/guju/.../student-h5/dist/"）
  local specs="$1"
  local out=""
  local cmd="" spec app dir
  for spec in $specs; do
    app="${spec%%:*}"
    dir="${spec#*:}"
    cmd="${cmd} if grep -q '${dir}' /etc/nginx/nginx.conf; then echo 'OK:${app}'; else echo 'MISS:${app}'; fi;"
  done
  # D4：复用 retry 执行器 + SSH_OPTS（追加 BatchMode 防交互挂起）；警告走 stderr，成功时 stdout 仅含校验行
  out=$(retry 3 5 ssh "${SSH_OPTS[@]}" -o BatchMode=yes "$SERVER" "$cmd" 2>&1) || {
    echo "⚠️  ssh 连接失败 3 次，无法校验 nginx 路径——请人工确认 nginx 配置后重跑"
    return 1
  }
  local miss="" line
  while read -r line; do
    case "$line" in
      OK:*)   echo "✅ nginx ${line#OK:} 路径指向校验通过" ;;
      MISS:*) miss="${miss} ${line#MISS:}" ;;
    esac
  done <<< "$out"
  if [ -n "$miss" ]; then
    echo "⚠️  nginx 未指向部署目标：${miss}——前端可能仍在服务旧目录！"
    echo "   修复: ssh ${SERVER} 修改 /etc/nginx/nginx.conf 对应 location 的 root/alias 后 nginx -t && nginx -s reload"
    return 1
  fi
  return 0
}
NGINX_PATH_FAIL=false
if $DEPLOY_STUDENT || $DEPLOY_TEACHER || $DEPLOY_PARENT; then
  dm_start nginx-check
  NGINX_SPECS=""
  $DEPLOY_STUDENT && NGINX_SPECS="${NGINX_SPECS} student:/guju/mindsafe/frontend/student-h5/dist/"
  $DEPLOY_TEACHER && NGINX_SPECS="${NGINX_SPECS} teacher:/guju/mindsafe/frontend/teacher-web/dist/"
  $DEPLOY_PARENT && NGINX_SPECS="${NGINX_SPECS} parent:/guju/mindsafe/frontend/parent-h5/dist/"
  ! check_nginx_paths "$NGINX_SPECS" && NGINX_PATH_FAIL=true
  dm_end nginx-check
fi
if [ "$NGINX_PATH_FAIL" = "true" ]; then
  echo "❌ 前端已部署但 nginx 路径未对齐，部署状态已更新；请按上方提示修复后重试"
  exit 1
fi

echo ""
echo "🎉 部署完成！组件:$COMPONENTS"
echo "   学生端：https://yun.gxjugu.com/mindsafe/"
echo "   教师端：https://yun.gxjugu.com/teacher/"
echo "   家长端：https://yun.gxjugu.com/parent/"
