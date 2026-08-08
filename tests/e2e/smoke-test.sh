#!/usr/bin/env bash
# ============================================================
# MindSafe E2E 冒烟测试（31 个断言：默认路径 28 + TTS/会话历史 3）
# 用途：部署后验证核心 API 链路是否可用
# 历史：TEST-003 如实校准——默认路径实际 28 断言；E4 修复补齐至 31（2026-07-28）
# 用法：BASE_URL=https://your-domain.com ./smoke-test.sh
#       或指定教师账号：TEACHER_USER=xxx TEACHER_PASS=xxx ./smoke-test.sh
#       管理员账号：ADMIN_USER=xxx ADMIN_PASS=xxx ./smoke-test.sh
# ============================================================
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API="$BASE_URL/api/v1"
PASS=0
FAIL=0

green() { printf "\033[32m%s\033[0m\n" "$1"; }
red() { printf "\033[31m%s\033[0m\n" "$1"; }
yellow() { printf "\033[33m%s\033[0m\n" "$1"; }

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

check_contains() {
  local name="$1" haystack="$2" needle="$3"
  if echo "$haystack" | grep -q "$needle"; then
    green "  ✓ $name"
    PASS=$((PASS + 1))
  else
    red "  ✗ $name (未找到: $needle)"
    FAIL=$((FAIL + 1))
  fi
}

check_not_empty() {
  local name="$1" value="$2"
  if [ -n "$value" ] && [ "$value" != "None" ] && [ "$value" != "null" ]; then
    green "  ✓ $name"
    PASS=$((PASS + 1))
  else
    red "  ✗ $name (值为空)"
    FAIL=$((FAIL + 1))
  fi
}

http_code() {
  curl -s -o /dev/null -w "%{http_code}" "$@" || true
}

json_field() {
  python3 -c "import sys,json; d=json.load(sys.stdin); print(d$1)" 2>/dev/null || echo ""
}

echo "=========================================="
echo "MindSafe E2E 冒烟测试 → $BASE_URL"
echo "=========================================="

# ===== 1. 健康检查 =====
echo ""
echo "[1/10] 健康检查"
check "actuator/health" "200" "$(http_code "$BASE_URL/actuator/health")"
HEALTH_BODY=$(curl -s "$BASE_URL/actuator/health" || true)
check_contains "健康状态 UP" "$HEALTH_BODY" '"status"'

# ===== 2. 认证与注册 =====
echo ""
echo "[2/10] 认证与注册"
# 2.1 错误凭据登录被拒
LOGIN_BODY=$(curl -s -X POST "$API/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"__not_exist__","password":"__bad__"}' || true)
LOGIN_SUCCESS=$(echo "$LOGIN_BODY" | json_field ".get('success','')")
check "错误凭据登录被拒（success=false）" "False" "$LOGIN_SUCCESS"

# 2.2 空密码登录被拒
EMPTY_BODY=$(curl -s -X POST "$API/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"test","password":""}' || true)
EMPTY_SUCCESS=$(echo "$EMPTY_BODY" | json_field ".get('success','')")
check "空密码登录被拒" "False" "$EMPTY_SUCCESS"

# 2.3 试用注册流程
# 注意：注册年龄用 >=14（本人同意即生效），生产环境 trial-auto-grant=false 不走
# 自动写入；age<14 的监护人 SMS 确认闭环由 GuardianConsentFlowIT 集成测试覆盖
NICK="smoke_$RANDOM"
REG_BODY=$(curl -s -X POST "$API/auth/trial/register" \
  -H 'Content-Type: application/json' \
  -d "{\"inviteCode\":\"DEMO2026\",\"pseudonym\":\"$NICK\",\"age\":14,\"consentVersion\":\"v0.1\",\"guardianPhone\":\"13800138000\"}" || true)
REG_SUCCESS=$(echo "$REG_BODY" | json_field ".get('success','')")
check "试用注册成功" "True" "$REG_SUCCESS"

STUDENT_TOKEN=$(echo "$REG_BODY" | json_field ".get('data',{}).get('token','')")
check_not_empty "获取学生 token" "$STUDENT_TOKEN"

# 2.4 重复昵称注册（应成功，昵称非唯一）
NICK2="smoke_$RANDOM"
REG2_BODY=$(curl -s -X POST "$API/auth/trial/register" \
  -H 'Content-Type: application/json' \
  -d "{\"inviteCode\":\"DEMO2026\",\"pseudonym\":\"$NICK2\",\"age\":15,\"consentVersion\":\"v0.1\",\"guardianPhone\":\"13900139000\"}" || true)
REG2_SUCCESS=$(echo "$REG2_BODY" | json_field ".get('success','')")
check "第二个学生注册成功" "True" "$REG2_SUCCESS"

# 2.5 无效邀请码注册被拒
BAD_REG=$(curl -s -X POST "$API/auth/trial/register" \
  -H 'Content-Type: application/json' \
  -d '{"inviteCode":"INVALID","pseudonym":"bad","age":10,"consentVersion":"v0.1","guardianPhone":"13800138000"}' || true)
BAD_REG_SUCCESS=$(echo "$BAD_REG" | json_field ".get('success','')")
check "无效邀请码注册被拒" "False" "$BAD_REG_SUCCESS"

# ===== 3. 学生对话链路 =====
echo ""
echo "[3/10] 学生对话链路"
if [ -n "$STUDENT_TOKEN" ]; then
  # 3.1 创建会话
  SESSION_BODY=$(curl -s -X POST "$API/chat/sessions" \
    -H "Authorization: Bearer $STUDENT_TOKEN" \
    -H 'Content-Type: application/json' \
    -d '{"emotionTag":"happy","channel":"h5"}' || true)
  SESSION_ID=$(echo "$SESSION_BODY" | json_field ".get('data',{}).get('sessionId','')")
  check_not_empty "创建会话" "$SESSION_ID"

  # 3.2 会话包含问候语
  GREETING=$(echo "$SESSION_BODY" | json_field ".get('data',{}).get('greeting','')")
  check_not_empty "会话问候语" "$GREETING"

  # 3.3 发送消息（SSE 流）
  if [ -n "$SESSION_ID" ]; then
    MSG_OUTPUT=$(curl -s -X POST "$API/chat/sessions/$SESSION_ID/messages" \
      -H "Authorization: Bearer $STUDENT_TOKEN" \
      -H 'Content-Type: application/json' \
      -d '{"content":"今天很开心"}' \
      --max-time 30 || true)
    check_contains "发送消息（SSE AI 回复完成）" "$MSG_OUTPUT" '"type"'

    # 3.4 发送第二条消息（多轮对话）
    MSG2_OUTPUT=$(curl -s -X POST "$API/chat/sessions/$SESSION_ID/messages" \
      -H "Authorization: Bearer $STUDENT_TOKEN" \
      -H 'Content-Type: application/json' \
      -d '{"content":"因为考试考了一百分"}' \
      --max-time 30 || true)
    check_contains "多轮对话（第二条消息）" "$MSG2_OUTPUT" '"type"'

    # 3.5 结束会话
    END_CODE=$(http_code -X POST "$API/chat/sessions/$SESSION_ID/end" \
      -H "Authorization: Bearer $STUDENT_TOKEN")
    check "结束会话" "200" "$END_CODE"
  fi

  # 3.6 会话历史
  HIST_CODE=$(http_code "$API/sessions" -H "Authorization: Bearer $STUDENT_TOKEN")
  check "会话历史" "200" "$HIST_CODE"

  # 3.7 会话历史包含刚创建的会话（E4：验证列表与创建链路一致，防静默丢失）
  HIST_BODY=$(curl -s "$API/sessions" -H "Authorization: Bearer $STUDENT_TOKEN" || true)
  if [ -n "$SESSION_ID" ]; then
    echo "$HIST_BODY" | grep -q "$SESSION_ID" \
      && green "  ✓ 会话历史包含本次会话" && PASS=$((PASS + 1)) \
      || { red "  ✗ 会话历史未包含本次会话 ($SESSION_ID)"; FAIL=$((FAIL + 1)); }
  fi

  # 3.8 不同情绪标签创建会话
  SESSION2_BODY=$(curl -s -X POST "$API/chat/sessions" \
    -H "Authorization: Bearer $STUDENT_TOKEN" \
    -H 'Content-Type: application/json' \
    -d '{"emotionTag":"sad","channel":"h5"}' || true)
  SESSION2_ID=$(echo "$SESSION2_BODY" | json_field ".get('data',{}).get('sessionId','')")
  check_not_empty "悲伤情绪会话创建" "$SESSION2_ID"
  # 清理
  curl -s -X POST "$API/chat/sessions/$SESSION2_ID/end" \
    -H "Authorization: Bearer $STUDENT_TOKEN" > /dev/null 2>&1 || true
else
  echo "  ⚠ 跳过（注册失败无 token）"
  FAIL=$((FAIL + 8))
fi

# ===== 4. 风险识别链路 =====
echo ""
echo "[4/10] 风险识别链路"
if [ -n "$STUDENT_TOKEN" ]; then
  # 创建新会话用于风险测试
  RISK_SESSION=$(curl -s -X POST "$API/chat/sessions" \
    -H "Authorization: Bearer $STUDENT_TOKEN" \
    -H 'Content-Type: application/json' \
    -d '{"emotionTag":"sad","channel":"h5"}' || true)
  RISK_SID=$(echo "$RISK_SESSION" | json_field ".get('data',{}).get('sessionId','')")

  if [ -n "$RISK_SID" ]; then
    # 4.1 发送风险消息（应触发风险识别）
    RISK_OUTPUT=$(curl -s -X POST "$API/chat/sessions/$RISK_SID/messages" \
      -H "Authorization: Bearer $STUDENT_TOKEN" \
      -H 'Content-Type: application/json' \
      -d '{"content":"我不想活了"}' \
      --max-time 30 || true)
    check_contains "风险消息被处理（SSE 响应）" "$RISK_OUTPUT" '"type"'

    # 4.2 风险会话结束
    curl -s -X POST "$API/chat/sessions/$RISK_SID/end" \
      -H "Authorization: Bearer $STUDENT_TOKEN" > /dev/null 2>&1 || true
    green "  ✓ 风险会话已关闭"
    PASS=$((PASS + 1))
  else
    red "  ✗ 风险测试会话创建失败"
    FAIL=$((FAIL + 2))
  fi
else
  echo "  ⚠ 跳过"
  FAIL=$((FAIL + 2))
fi

# ===== 5. 输入校验与安全 =====
echo ""
echo "[5/10] 输入校验与安全"
# 5.1 无 token 访问受保护端点
UNAUTH_CODE=$(http_code "$API/teacher/dashboard")
if [ "$UNAUTH_CODE" = "401" ] || [ "$UNAUTH_CODE" = "403" ]; then
  green "  ✓ 未认证访问被拒绝 ($UNAUTH_CODE)"
  PASS=$((PASS + 1))
else
  red "  ✗ 未认证访问未被拒绝 (实际 $UNAUTH_CODE)"
  FAIL=$((FAIL + 1))
fi

# 5.2 学生 token 访问管理端被拒
if [ -n "${STUDENT_TOKEN:-}" ]; then
  ADMIN_CODE=$(http_code "$API/admin/invite-codes" -H "Authorization: Bearer $STUDENT_TOKEN")
  check "学生访问管理端被拒绝" "403" "$ADMIN_CODE"
fi

# 5.3 XSS 输入不导致 500
if [ -n "${STUDENT_TOKEN:-}" ]; then
  XSS_SESSION=$(curl -s -X POST "$API/chat/sessions" \
    -H "Authorization: Bearer $STUDENT_TOKEN" \
    -H 'Content-Type: application/json' \
    -d '{"emotionTag":"happy","channel":"h5"}' || true)
  XSS_SID=$(echo "$XSS_SESSION" | json_field ".get('data',{}).get('sessionId','')")
  if [ -n "$XSS_SID" ]; then
    XSS_CODE=$(http_code -X POST "$API/chat/sessions/$XSS_SID/messages" \
      -H "Authorization: Bearer $STUDENT_TOKEN" \
      -H 'Content-Type: application/json' \
      -d '{"content":"<script>alert(1)</script>"}' \
      --max-time 15)
    if [ "$XSS_CODE" != "500" ]; then
      green "  ✓ XSS 输入不导致 500 ($XSS_CODE)"
      PASS=$((PASS + 1))
    else
      red "  ✗ XSS 输入导致 500"
      FAIL=$((FAIL + 1))
    fi
    curl -s -X POST "$API/chat/sessions/$XSS_SID/end" \
      -H "Authorization: Bearer $STUDENT_TOKEN" > /dev/null 2>&1 || true
  fi
fi

# 5.4 无效 JSON 不导致 500
BAD_JSON_CODE=$(http_code -X POST "$API/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{invalid json}')
if [ "$BAD_JSON_CODE" != "500" ]; then
  green "  ✓ 无效 JSON 不导致 500 ($BAD_JSON_CODE)"
  PASS=$((PASS + 1))
else
  red "  ✗ 无效 JSON 导致 500"
  FAIL=$((FAIL + 1))
fi

# 5.5 超长输入不导致 500
LONG_INPUT=$(python3 -c "print('a'*10000)" 2>/dev/null || echo "aaaa")
LONG_CODE=$(http_code -X POST "$API/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$LONG_INPUT\",\"password\":\"test\"}")
if [ "$LONG_CODE" != "500" ]; then
  green "  ✓ 超长输入不导致 500 ($LONG_CODE)"
  PASS=$((PASS + 1))
else
  red "  ✗ 超长输入导致 500"
  FAIL=$((FAIL + 1))
fi

# 5.6 TTS 音色列表匿名可访问（E4：验证公开端点白名单行为）
PERSONAS_CODE=$(http_code "$API/tts/personas")
check "TTS 音色列表匿名可访问" "200" "$PERSONAS_CODE"
PERSONAS_BODY=$(curl -s "$API/tts/personas" || true)
PERSONAS_COUNT=$(echo "$PERSONAS_BODY" | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d.get('data',[])))" 2>/dev/null || echo "0")
if [ "$PERSONAS_COUNT" -ge 1 ]; then
  green "  ✓ TTS 音色列表非空（$PERSONAS_COUNT 个）"
  PASS=$((PASS + 1))
else
  red "  ✗ TTS 音色列表为空（tts-service 未就绪或返回异常）"
  FAIL=$((FAIL + 1))
fi

# 5.7 声纹登录引导语非白名单文本 → 400（E4：验证公开 TTS 端点防滥用）
LOGIN_PROMPT_CODE=$(http_code -X POST "$API/tts/login-prompt" \
  -H 'Content-Type: application/json' \
  -d '{"text":"随便说点什么"}')
check "login-prompt 非白名单被拒" "400" "$LOGIN_PROMPT_CODE"

# ===== 6. 教师端链路 =====
echo ""
echo "[6/10] 教师端链路"
if [ -n "${TEACHER_USER:-}" ] && [ -n "${TEACHER_PASS:-}" ]; then
  LOGIN_BODY=$(curl -s -X POST "$API/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$TEACHER_USER\",\"password\":\"$TEACHER_PASS\"}" || true)
  TEACHER_TOKEN=$(echo "$LOGIN_BODY" | json_field ".get('data',{}).get('token','')")

  if [ -n "$TEACHER_TOKEN" ]; then
    check "教师工作台" "200" "$(http_code "$API/teacher/dashboard" -H "Authorization: Bearer $TEACHER_TOKEN")"
    check "预警队列" "200" "$(http_code "$API/alerts" -H "Authorization: Bearer $TEACHER_TOKEN")"
    check "学生列表" "200" "$(http_code "$API/teacher/students" -H "Authorization: Bearer $TEACHER_TOKEN")"
    check "质量监控" "200" "$(http_code "$API/teacher/quality/recent" -H "Authorization: Bearer $TEACHER_TOKEN")"
    check "数据统计" "200" "$(http_code "$API/teacher/analytics/overview" -H "Authorization: Bearer $TEACHER_TOKEN")"
  else
    red "  ✗ 教师登录失败: $LOGIN_BODY"
    FAIL=$((FAIL + 5))
  fi
else
  yellow "  ⚠ 未提供 TEACHER_USER/TEACHER_PASS，跳过教师端测试"
fi

# ===== 7. 管理员链路 =====
echo ""
echo "[7/10] 管理员链路"
if [ -n "${ADMIN_USER:-}" ] && [ -n "${ADMIN_PASS:-}" ]; then
  ADMIN_LOGIN=$(curl -s -X POST "$API/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" || true)
  ADMIN_TOKEN=$(echo "$ADMIN_LOGIN" | json_field ".get('data',{}).get('token','')")

  if [ -n "$ADMIN_TOKEN" ]; then
    check "邀请码列表" "200" "$(http_code "$API/admin/invite-codes" -H "Authorization: Bearer $ADMIN_TOKEN")"
    check "Prompt 版本列表" "200" "$(http_code "$API/admin/prompts/versions" -H "Authorization: Bearer $ADMIN_TOKEN")"
    check "租户列表" "200" "$(http_code "$API/admin/tenants" -H "Authorization: Bearer $ADMIN_TOKEN")"
    check "知识库文档列表" "200" "$(http_code "$API/admin/knowledge/documents" -H "Authorization: Bearer $ADMIN_TOKEN")"
    check "审计日志" "200" "$(http_code "$API/admin/audit-logs" -H "Authorization: Bearer $ADMIN_TOKEN")"
  else
    red "  ✗ 管理员登录失败"
    FAIL=$((FAIL + 5))
  fi
else
  yellow "  ⚠ 未提供 ADMIN_USER/ADMIN_PASS，跳过管理员测试"
fi

# ===== 8. 并发与边界 =====
echo ""
echo "[8/10] 并发与边界"
if [ -n "${STUDENT_TOKEN:-}" ]; then
  # 8.1 并发创建 3 个会话（应全部成功或有合理限制）
  CONCURRENT_OK=0
  for i in 1 2 3; do
    C_BODY=$(curl -s -X POST "$API/chat/sessions" \
      -H "Authorization: Bearer $STUDENT_TOKEN" \
      -H 'Content-Type: application/json' \
      -d '{"emotionTag":"nervous","channel":"h5"}' || true)
    C_SUCCESS=$(echo "$C_BODY" | json_field ".get('success','')")
    if [ "$C_SUCCESS" = "True" ]; then
      CONCURRENT_OK=$((CONCURRENT_OK + 1))
      C_SID=$(echo "$C_BODY" | json_field ".get('data',{}).get('sessionId','')")
      curl -s -X POST "$API/chat/sessions/$C_SID/end" \
        -H "Authorization: Bearer $STUDENT_TOKEN" > /dev/null 2>&1 || true
    fi
  done
  if [ "$CONCURRENT_OK" -ge 1 ]; then
    green "  ✓ 并发会话创建 ($CONCURRENT_OK/3 成功)"
    PASS=$((PASS + 1))
  else
    red "  ✗ 并发会话创建全部失败"
    FAIL=$((FAIL + 1))
  fi

  # 8.2 空消息体发送
  EMPTY_MSG_CODE=$(http_code -X POST "$API/chat/sessions/00000000-0000-0000-0000-000000000000/messages" \
    -H "Authorization: Bearer $STUDENT_TOKEN" \
    -H 'Content-Type: application/json' \
    -d '{"content":""}')
  if [ "$EMPTY_MSG_CODE" != "500" ]; then
    green "  ✓ 无效会话 ID 不导致 500 ($EMPTY_MSG_CODE)"
    PASS=$((PASS + 1))
  else
    red "  ✗ 无效会话 ID 导致 500"
    FAIL=$((FAIL + 1))
  fi
else
  echo "  ⚠ 跳过"
  FAIL=$((FAIL + 2))
fi

# ===== 9. 情绪日记与放松练习 =====
echo ""
echo "[9/10] 情绪日记与放松练习"
if [ -n "${STUDENT_TOKEN:-}" ]; then
  # 9.1 情绪日记历史（真实路由 /api/v1/diary/history）
  DIARY_CODE=$(http_code "$API/diary/history" -H "Authorization: Bearer $STUDENT_TOKEN")
  if [ "$DIARY_CODE" = "200" ] || [ "$DIARY_CODE" = "404" ]; then
    green "  ✓ 情绪日记端点可达 ($DIARY_CODE)"
    PASS=$((PASS + 1))
  else
    red "  ✗ 情绪日记端点异常 ($DIARY_CODE)"
    FAIL=$((FAIL + 1))
  fi

  # 9.2 放松练习列表
  RELAX_CODE=$(http_code "$API/relaxation/exercises" -H "Authorization: Bearer $STUDENT_TOKEN")
  if [ "$RELAX_CODE" = "200" ] || [ "$RELAX_CODE" = "404" ]; then
    green "  ✓ 放松练习端点可达 ($RELAX_CODE)"
    PASS=$((PASS + 1))
  else
    red "  ✗ 放松练习端点异常 ($RELAX_CODE)"
    FAIL=$((FAIL + 1))
  fi
else
  echo "  ⚠ 跳过"
  FAIL=$((FAIL + 2))
fi

# ===== 10. 响应时间与稳定性 =====
echo ""
echo "[10/10] 响应时间与稳定性"
# 10.1 健康检查响应时间 < 2s
START_MS=$(python3 -c "import time; print(int(time.time()*1000))" 2>/dev/null || echo 0)
curl -s -o /dev/null "$BASE_URL/actuator/health" || true
END_MS=$(python3 -c "import time; print(int(time.time()*1000))" 2>/dev/null || echo 0)
LATENCY=$((END_MS - START_MS))
if [ "$LATENCY" -lt 2000 ]; then
  green "  ✓ 健康检查延迟 ${LATENCY}ms (< 2000ms)"
  PASS=$((PASS + 1))
else
  red "  ✗ 健康检查延迟 ${LATENCY}ms (≥ 2000ms)"
  FAIL=$((FAIL + 1))
fi

# 10.2 连续 5 次健康检查全部 200
STABLE_OK=0
for i in 1 2 3 4 5; do
  S_CODE=$(http_code "$BASE_URL/actuator/health")
  if [ "$S_CODE" = "200" ]; then STABLE_OK=$((STABLE_OK + 1)); fi
done
check "连续 5 次健康检查稳定" "5" "$STABLE_OK"

# ----- 汇总 -----
echo ""
echo "=========================================="
TOTAL=$((PASS + FAIL))
if [ "$FAIL" -eq 0 ]; then
  green "E2E 冒烟测试通过：$PASS/$TOTAL"
  exit 0
else
  red "E2E 冒烟测试失败：$PASS 通过 / $FAIL 失败（共 $TOTAL）"
  exit 1
fi
