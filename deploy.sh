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

SERVER="mindsafe@116.8.109.229"
REMOTE_DIR="/guju/mindsafe"
PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
STATE_FILE="$PROJECT_ROOT/.deploy-state"
JAR="counseling-app/target/counseling-app-0.1.0-SNAPSHOT.jar"

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
if [ -n "$ROLLBACK_TARGET" ]; then
  echo "⏪ 回滚 $ROLLBACK_TARGET..."
  case "$ROLLBACK_TARGET" in
    backend)
      ssh "$SERVER" "cd $REMOTE_DIR && [ -f app.jar.prev ] && cp app.jar.prev app.jar && docker compose up -d --build backend"
      echo "✅ 后端已回滚到上一版本"
      ;;
    tts)
      ssh "$SERVER" "cd $REMOTE_DIR && docker compose up -d --build tts-service"
      echo "✅ TTS 服务已重建"
      ;;
    voice)
      ssh "$SERVER" "cd $REMOTE_DIR && docker compose up -d --build voice-service"
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
  echo "📦 构建后端..."
  cd "$PROJECT_ROOT/backend"
  mvn clean package -DskipTests -pl counseling-app -am -q
  if [ ! -f "$JAR" ]; then
    echo "❌ JAR 构建失败"; exit 1
  fi
  echo "✅ 后端 JAR 就绪"
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

# ===== 选择性上传 =====
echo ""
echo "🚀 部署到服务器..."

if $DEPLOY_BACKEND; then
  # 保留上一版本用于回滚
  ssh "$SERVER" "[ -f $REMOTE_DIR/app.jar ] && cp $REMOTE_DIR/app.jar $REMOTE_DIR/app.jar.prev || true"
  rsync_retry -avz "$PROJECT_ROOT/backend/$JAR" "$SERVER:$REMOTE_DIR/app.jar"
fi

$DEPLOY_STUDENT && rsync_retry -avz --delete --exclude 'models/' "$PROJECT_ROOT/frontend/student-h5/dist/" "$SERVER:$REMOTE_DIR/frontend/student/"
$DEPLOY_TEACHER && rsync_retry -avz --delete "$PROJECT_ROOT/frontend/teacher-web/dist/" "$SERVER:$REMOTE_DIR/frontend/teacher/"
$DEPLOY_PARENT && rsync_retry -avz --delete "$PROJECT_ROOT/frontend/parent-h5/dist/" "$SERVER:$REMOTE_DIR/frontend/parent/"


if $DEPLOY_TTS; then
  rsync_retry -avz --delete "$PROJECT_ROOT/backend/tts-service/" "$SERVER:$REMOTE_DIR/tts-service/"
fi

if $DEPLOY_VOICE; then
  rsync_retry -avz --delete "$PROJECT_ROOT/backend/voice-service/" "$SERVER:$REMOTE_DIR/voice-service/"
fi

# ===== 选择性重启（仅 backend/tts 需要） =====
RESTART_SERVICES=""
$DEPLOY_BACKEND && RESTART_SERVICES="$RESTART_SERVICES backend"
$DEPLOY_TTS && RESTART_SERVICES="$RESTART_SERVICES tts-service"
$DEPLOY_VOICE && RESTART_SERVICES="$RESTART_SERVICES voice-service"

if [ -n "$RESTART_SERVICES" ]; then
  echo "🔄 重启容器:$RESTART_SERVICES"
  ssh "$SERVER" "cd $REMOTE_DIR && docker compose up -d --build $RESTART_SERVICES"

  # 健康检查（仅重启时，轮询最多 60s）
  echo "⏳ 等待服务启动..."
  if $DEPLOY_BACKEND; then
    HEALTH_OK=false
    for i in $(seq 1 12); do
      sleep 5
      HTTP_OK=$(ssh "$SERVER" "curl -sf http://127.0.0.1:18081/actuator/health > /dev/null && echo yes || echo no" 2>/dev/null)
      if [ "$HTTP_OK" = "yes" ]; then
        HEALTH_OK=true
        break
      fi
      echo "   等待中... (${i}/12)"
    done
    if $HEALTH_OK; then
      echo "✅ 后端健康检查通过"
    else
      echo "❌ 后端健康检查失败（60s 超时），请检查：ssh $SERVER 'docker logs mindsafe-backend-1 --tail 30'"
      echo "   部署状态未更新，下次 deploy.sh 将重新部署"
      exit 1
    fi
  else
    sleep 10
  fi
else
  echo "✅ 前端静态文件已更新（无需重启容器）"
fi

# ===== 更新部署状态（仅在部署成功后） =====
echo "LAST_DEPLOYED_COMMIT=$LOCAL" > "$STATE_FILE"
echo "DEPLOYED_AT=$(date -Iseconds)" >> "$STATE_FILE"

echo ""
echo "🎉 部署完成！组件:$COMPONENTS"
echo "   学生端：https://yun.gxjugu.com/mindsafe/"
echo "   教师端：https://yun.gxjugu.com/teacher/"
echo "   家长端：https://yun.gxjugu.com/parent/"
