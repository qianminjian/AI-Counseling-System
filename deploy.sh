#!/bin/bash
# MindSafe 生产部署脚本（本地执行）
# 用法：./deploy.sh
# 前置条件：已 commit + push，CI 通过
set -eo pipefail

SERVER="mindsafe@116.8.109.229"
REMOTE_DIR="/guju/mindsafe"
PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"

echo "🔍 前置检查..."

# 1. 确认工作区干净（无未提交变更）
if [ -n "$(git status --porcelain)" ]; then
  echo "❌ 有未提交的变更，请先 commit"
  git status --short
  exit 1
fi

# 2. 确认已 push（本地 HEAD = 远程 HEAD）
git fetch origin main --quiet
LOCAL=$(git rev-parse HEAD)
REMOTE=$(git rev-parse origin/main)
if [ "$LOCAL" != "$REMOTE" ]; then
  echo "❌ 本地 HEAD 与 origin/main 不一致，请先 push"
  echo "   local:  $LOCAL"
  echo "   remote: $REMOTE"
  exit 1
fi

echo "✅ Git 状态正常（已 commit + push）"
echo ""

# 3. 构建后端 JAR
echo "📦 构建后端..."
cd "$PROJECT_ROOT/backend"
mvn package -DskipTests -pl counseling-app -am -q
JAR="counseling-app/target/counseling-app-0.1.0-SNAPSHOT.jar"
if [ ! -f "$JAR" ]; then
  echo "❌ JAR 构建失败"
  exit 1
fi
echo "✅ 后端 JAR 就绪"

# 4. 构建前端
echo "📦 构建学生端..."
cd "$PROJECT_ROOT/frontend/student-h5"
npm run build --silent
echo "✅ 学生端构建完成"

echo "📦 构建教师端..."
cd "$PROJECT_ROOT/frontend/teacher-web"
npm run build --silent
echo "✅ 教师端构建完成"

echo "📦 构建家长端..."
cd "$PROJECT_ROOT/frontend/parent-h5"
npm run build --silent
echo "✅ 家长端构建完成"

# 5. 上传到服务器
echo ""
echo "🚀 部署到服务器..."

rsync -avz --delete "$PROJECT_ROOT/frontend/student-h5/dist/" "$SERVER:$REMOTE_DIR/frontend/student/"
rsync -avz --delete "$PROJECT_ROOT/frontend/teacher-web/dist/" "$SERVER:$REMOTE_DIR/frontend/teacher/"
rsync -avz --delete "$PROJECT_ROOT/frontend/parent-h5/dist/" "$SERVER:$REMOTE_DIR/frontend/parent/"
rsync -avz "$PROJECT_ROOT/backend/$JAR" "$SERVER:$REMOTE_DIR/app.jar"

# 6. 重建后端容器
echo "🔄 重启后端容器..."
ssh "$SERVER" "cd $REMOTE_DIR && docker compose up -d --build backend"

# 7. 等待启动 + 健康检查
echo "⏳ 等待服务启动..."
sleep 12
HTTP_CODE=$(ssh "$SERVER" "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18081/actuator/health" 2>/dev/null | tr -cd '0-9' || echo "000")
HTTP_CODE=${HTTP_CODE:-000}

if [ "$HTTP_CODE" = "200" ]; then
  echo ""
  echo "🎉 部署完成！服务已启动（HTTP $HTTP_CODE）"
  echo "   学生端：https://yun.gxjugu.com/mindsafe/"
  echo "   教师端：https://yun.gxjugu.com/teacher/"
  echo "   家长端：https://yun.gxjugu.com/parent/"
else
  echo "⚠️  服务可能未完全启动（HTTP $HTTP_CODE），请手动检查：ssh $SERVER 'docker logs mindsafe-backend-1 --tail 20'"
fi
