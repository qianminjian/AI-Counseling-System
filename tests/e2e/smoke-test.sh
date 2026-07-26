#!/usr/bin/env bash
# ============================================================
# MindSafe E2E 冒烟测试
# 用途：部署后验证核心 API 链路是否可用
# 用法：BASE_URL=https://your-domain.com ./smoke-test.sh
#       或指定教师账号：TEACHER_USER=xxx TEACHER_PASS=xxx ./smoke-test.sh
# ============================================================
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API="$BASE_URL/api/v1"
PASS=0
FAIL=0

green() { printf "\033[32m%s\033[0m\n" "$1"; }
red() { printf "\033[31m%s\033[0m\n" "$1"; }

check() {
  local name="$1" expected="$2" actual="$3"
  if [ "$actual" = "$expected" ]; then
    green "  ✓ $name"
    PASS=$((PASS + 1))
  else
    red "  ✗ $name (期望 $expected，实际 $actual)"
    FAIL=$((FAIL + 1))
  fi
}

http_code() {
  curl -s -o /dev/null -w "%{http_code}" "$@"
}

echo "=========================================="
echo "MindSafe 冒烟测试 → $BASE_URL"
echo "=========================================="

# ----- 1. 健康检查 -----
echo ""
echo "[1/6] 健康检查"
check "actuator/health" "200" "$(http_code "$BASE_URL/actuator/health")"

# ----- 2. 公开端点 -----
echo ""
echo "[2/6] 公开端点"
check "登录页可达（错误凭据返回 401）" "401" \
  "$(http_code -X POST "$API/auth/login" -H 'Content-Type: application/json' -d '{"username":"__not_exist__","password":"__bad__"}')"

# ----- 3. 试用注册流程 -----
echo ""
echo "[3/6] 试用注册流程"
NICK="smoke_$RANDOM"
REG_BODY=$(curl -s -X POST "$API/auth/trial/register" \
  -H 'Content-Type: application/json' \
  -d "{\"inviteCode\":\"DEMO2026\",\"pseudonym\":\"$NICK\",\"age\":9,\"consentVersion\":\"v1.0\"}")
REG_SUCCESS=$(echo "$REG_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('success',''))" 2>/dev/null || echo "")
check "试用注册成功" "True" "$REG_SUCCESS"

STUDENT_TOKEN=$(echo "$REG_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('token',''))" 2>/dev/null || echo "")

if [ -n "$STUDENT_TOKEN" ]; then
  # ----- 4. 学生对话链路 -----
  echo ""
  echo "[4/6] 学生对话链路"

  # 创建会话
  SESSION_BODY=$(curl -s -X POST "$API/chat/sessions" \
    -H "Authorization: Bearer $STUDENT_TOKEN" \
    -H 'Content-Type: application/json' \
    -d '{"emotionTag":"happy","channel":"h5"}')
  SESSION_ID=$(echo "$SESSION_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('sessionId',''))" 2>/dev/null || echo "")
  if [ -n "$SESSION_ID" ]; then
    green "  ✓ 创建会话 ($SESSION_ID)"
    PASS=$((PASS + 1))
  else
    red "  ✗ 创建会话失败: $SESSION_BODY"
    FAIL=$((FAIL + 1))
  fi

  # 发送消息（SSE 流，取 HTTP 状态码）
  if [ -n "$SESSION_ID" ]; then
    MSG_CODE=$(http_code -X POST "$API/chat/sessions/$SESSION_ID/messages" \
      -H "Authorization: Bearer $STUDENT_TOKEN" \
      -H 'Content-Type: application/json' \
      -d '{"content":"今天很开心"}' \
      --max-time 30)
    check "发送消息（SSE）" "200" "$MSG_CODE"

    # 结束会话
    END_CODE=$(http_code -X POST "$API/chat/sessions/$SESSION_ID/end" \
      -H "Authorization: Bearer $STUDENT_TOKEN")
    check "结束会话" "200" "$END_CODE"
  fi

  # 会话历史
  HIST_CODE=$(http_code "$API/sessions" -H "Authorization: Bearer $STUDENT_TOKEN")
  check "会话历史" "200" "$HIST_CODE"
else
  echo ""
  echo "[4/6] 学生对话链路 — 跳过（注册失败无 token）"
  FAIL=$((FAIL + 3))
fi

# ----- 5. 教师端链路 -----
echo ""
echo "[5/6] 教师端链路"
if [ -n "${TEACHER_USER:-}" ] && [ -n "${TEACHER_PASS:-}" ]; then
  LOGIN_BODY=$(curl -s -X POST "$API/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$TEACHER_USER\",\"password\":\"$TEACHER_PASS\"}")
  TEACHER_TOKEN=$(echo "$LOGIN_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('token',''))" 2>/dev/null || echo "")

  if [ -n "$TEACHER_TOKEN" ]; then
    check "教师工作台" "200" "$(http_code "$API/teacher/dashboard" -H "Authorization: Bearer $TEACHER_TOKEN")"
    check "预警队列" "200" "$(http_code "$API/alerts" -H "Authorization: Bearer $TEACHER_TOKEN")"
    check "学生列表" "200" "$(http_code "$API/teacher/students" -H "Authorization: Bearer $TEACHER_TOKEN")"
  else
    red "  ✗ 教师登录失败: $LOGIN_BODY"
    FAIL=$((FAIL + 3))
  fi
else
  echo "  ⚠ 未提供 TEACHER_USER/TEACHER_PASS，跳过教师端测试"
fi

# ----- 6. 安全校验 -----
echo ""
echo "[6/6] 安全校验"
# 无 token 访问教师端 → 401/403
UNAUTH_CODE=$(http_code "$API/teacher/dashboard")
if [ "$UNAUTH_CODE" = "401" ] || [ "$UNAUTH_CODE" = "403" ]; then
  green "  ✓ 未认证访问教师端被拒绝 ($UNAUTH_CODE)"
  PASS=$((PASS + 1))
else
  red "  ✗ 未认证访问教师端未被拒绝 (实际 $UNAUTH_CODE)"
  FAIL=$((FAIL + 1))
fi

# 学生 token 访问管理端 → 403
if [ -n "${STUDENT_TOKEN:-}" ]; then
  ADMIN_CODE=$(http_code "$API/admin/invite-codes" -H "Authorization: Bearer $STUDENT_TOKEN")
  check "学生访问管理端被拒绝" "403" "$ADMIN_CODE"
fi

# ----- 汇总 -----
echo ""
echo "=========================================="
TOTAL=$((PASS + FAIL))
if [ "$FAIL" -eq 0 ]; then
  green "冒烟测试通过：$PASS/$TOTAL"
  exit 0
else
  red "冒烟测试失败：$PASS 通过 / $FAIL 失败（共 $TOTAL）"
  exit 1
fi
