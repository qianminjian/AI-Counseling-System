#!/bin/bash
# MindSafe 增量部署脚本（本地执行）
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
# 前置条件：已 commit + push，CI 通过
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
if [ -z "${SERVER}" ]; then
  echo "ERROR: 必须设置 MINDSAFE_SERVER（如 export MINDSAFE_SERVER=mindsafe@<服务器IP>）"
  exit 1
fi
REMOTE_DIR="/guju/mindsafe"
PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
STATE_FILE="$PROJECT_ROOT/.deploy-state"
# 服务器上 compose 位于 $REMOTE_DIR/deploy/，挂载/构建上下文为仓库镜像结构：
#   ../frontend/<app>/dist（nginx 挂载）、../backend/<svc>（compose build context）
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
      ssh "$SERVER" "cd $REMOTE_DIR/deploy && docker compose -f docker-compose.prod.yml build backend && docker compose -f docker-compose.prod.yml up -d backend"
      echo "✅ 后端已重建"
      ;;
    tts)
      ssh "$SERVER" "cd $REMOTE_DIR/deploy && docker compose -f docker-compose.prod.yml build tts-service && docker compose -f docker-compose.prod.yml up -d tts-service"
      echo "✅ TTS 服务已重建"
      ;;
    voice)
      ssh "$SERVER" "cd $REMOTE_DIR/deploy && docker compose -f docker-compose.prod.yml build voice-service && docker compose -f docker-compose.prod.yml up -d voice-service"
      echo "✅ Voice 服务已重建"
      ;;
    *)
      echo "❌ 不支持回滚: $ROLLBACK_TARGET（支持 backend / tts / voice）"
      exit 1
      ;;
  esac
  exit 0
fi

# ===== 前置检查 =====
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

# ===== 变更检测 =====
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
  echo "✅ 无需部署的组件变更"
  # 仍更新状态文件（可能有 design/ 等无需部署的变更）
  echo "LAST_DEPLOYED_COMMIT=$LOCAL" > "$STATE_FILE"
  echo "DEPLOYED_AT=$(date -Iseconds)" >> "$STATE_FILE"
  exit 0
fi

echo "🎯 待部署组件:$COMPONENTS"
echo ""

# ===== 选择性构建 =====
if $DEPLOY_BACKEND; then
  echo "📦 后端：本地预检编译（服务器 compose build 会重新打包）..."
  cd "$PROJECT_ROOT/backend"
  mvn -q compile -DskipTests -pl counseling-app -am || { echo "❌ 后端编译失败，中止部署"; exit 1; }
  echo "✅ 后端编译通过"
fi

if $DEPLOY_STUDENT; then
  echo "📦 构建学生端..."
  cd "$PROJECT_ROOT/frontend/student-h5"
  npm run build --silent
  echo "✅ 学生端构建完成"
fi

if $DEPLOY_TEACHER; then
  echo "📦 构建教师端..."
  cd "$PROJECT_ROOT/frontend/teacher-web"
  npm run build --silent
  echo "✅ 教师端构建完成"
fi

if $DEPLOY_PARENT; then
  echo "📦 构建家长端..."
  cd "$PROJECT_ROOT/frontend/parent-h5"
  npm run build --silent
  echo "✅ 家长端构建完成"
fi

# ===== rsync 重试封装 =====
rsync_retry() {
  local max_attempts=3
  local attempt=1
  while [ $attempt -le $max_attempts ]; do
    if rsync "$@"; then
      return 0
    fi
    echo "⚠️  rsync 第 $attempt 次失败，${attempt}s 后重试..."
    sleep $attempt
    attempt=$((attempt + 1))
  done
  echo "❌ rsync 重试 $max_attempts 次后仍失败"
  return 1
}

# ===== 选择性上传（路径与 deploy/docker-compose.prod.yml 挂载/构建上下文对齐） =====
echo ""
echo "🚀 部署到服务器..."

if $DEPLOY_BACKEND; then
  # backend compose build context = ../backend（多阶段源码构建），排除构建产物与大文件
  rsync_retry -avz --delete \
    --exclude 'target/' --exclude '.git/' \
    --exclude 'tts-service/wheels/' \
    "$PROJECT_ROOT/backend/" "$SERVER:$REMOTE_DIR/backend/"
  # 同步 backend 根 Dockerfile（context=backend）
fi

$DEPLOY_STUDENT && rsync_retry -avz --delete --exclude 'models/' "$PROJECT_ROOT/frontend/student-h5/dist/" "$SERVER:$REMOTE_DIR/frontend/student-h5/dist/"
$DEPLOY_TEACHER && rsync_retry -avz --delete "$PROJECT_ROOT/frontend/teacher-web/dist/" "$SERVER:$REMOTE_DIR/frontend/teacher-web/dist/"
$DEPLOY_PARENT && rsync_retry -avz --delete "$PROJECT_ROOT/frontend/parent-h5/dist/" "$SERVER:$REMOTE_DIR/frontend/parent-h5/dist/"


if $DEPLOY_TTS; then
  # 审计 P2-24：wheels/ 为可再生的本地构建产物，不再随 rsync 上传（Dockerfile 已改在线安装）
  rsync_retry -avz --delete --exclude 'wheels/' "$PROJECT_ROOT/backend/tts-service/" "$SERVER:$REMOTE_DIR/backend/tts-service/"
fi

if $DEPLOY_VOICE; then
  rsync_retry -avz --delete "$PROJECT_ROOT/backend/voice-service/" "$SERVER:$REMOTE_DIR/backend/voice-service/"
fi

# 同步 deploy/ 目录（compose + nginx 配置 + 运维脚本），变更检测命中 deploy/ 时为全量部署
rsync_retry -avz --exclude '.env' "$PROJECT_ROOT/deploy/" "$SERVER:$REMOTE_DIR/deploy/"

# ===== 上传 service-manager.sh（确保服务器有最新版本） =====
rsync_retry -avz "$PROJECT_ROOT/service-manager.sh" "$SERVER:$REMOTE_DIR/service-manager.sh"
ssh "$SERVER" "chmod +x $REMOTE_DIR/service-manager.sh"

# ===== 构建镜像（源码变更类组件需先 build，再经 service-manager 重启） =====
BUILD_TARGETS=""
$DEPLOY_BACKEND && BUILD_TARGETS="$BUILD_TARGETS backend"
$DEPLOY_TTS && BUILD_TARGETS="$BUILD_TARGETS tts-service"
$DEPLOY_VOICE && BUILD_TARGETS="$BUILD_TARGETS voice-service"

if [ -n "$BUILD_TARGETS" ]; then
  echo "🔨 构建镜像:$BUILD_TARGETS"
  if ! ssh "$SERVER" "cd $REMOTE_DIR/deploy && docker compose -f docker-compose.prod.yml build $BUILD_TARGETS"; then
    echo "❌ 镜像构建失败，部署中止（服务仍运行旧版本）"
    exit 1
  fi
  echo "✅ 镜像构建完成"
fi

# ===== 选择性重启（通过 service-manager.sh 统一管理） =====
RESTART_TARGETS=""
$DEPLOY_BACKEND && RESTART_TARGETS="$RESTART_TARGETS backend"
$DEPLOY_TTS && RESTART_TARGETS="$RESTART_TARGETS tts"
$DEPLOY_VOICE && RESTART_TARGETS="$RESTART_TARGETS voice"
# 前端 dist 由 nginx 容器挂载，静态文件更新后需重载 nginx 才生效
{ $DEPLOY_STUDENT || $DEPLOY_TEACHER || $DEPLOY_PARENT; } && RESTART_TARGETS="$RESTART_TARGETS nginx"

if [ -n "$RESTART_TARGETS" ]; then
  echo "🔄 重启服务:$RESTART_TARGETS"
  if ! ssh "$SERVER" "cd $REMOTE_DIR && bash service-manager.sh restart $RESTART_TARGETS"; then
    echo "❌ 服务重启失败，请检查：ssh $SERVER 'cd $REMOTE_DIR && bash service-manager.sh status'"
    echo "   部署状态未更新，下次 deploy.sh 将重新部署"
    exit 1
  fi
  echo "✅ 服务重启 + 健康检查通过"
else
  echo "ℹ️  仅前端变更时也应重载 nginx（已纳入重启目标）"
fi

# ===== 更新部署状态（仅在部署成功后） =====
echo "LAST_DEPLOYED_COMMIT=$LOCAL" > "$STATE_FILE"
echo "DEPLOYED_AT=$(date -Iseconds)" >> "$STATE_FILE"

echo ""
echo "🎉 部署完成！组件:$COMPONENTS"
echo "   学生端：https://yun.gxjugu.com/mindsafe/"
echo "   教师端：https://yun.gxjugu.com/teacher/"
echo "   家长端：https://yun.gxjugu.com/parent/"
