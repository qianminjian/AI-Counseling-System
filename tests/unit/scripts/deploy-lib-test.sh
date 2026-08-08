#!/usr/bin/env bash
# ============================================================
# DA-11: deploy/scripts/deploy-lib.sh 行为测试（TDD）
# 验证：路径映射规则（backend/tts/voice/三端/deploy 全量）、
#       retry 执行器（重试次数/递增退避/失败语义）、
#       nginx 校验命令构建与输出解析
# 用法：bash tests/unit/scripts/deploy-lib-test.sh
# ============================================================
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")/../../.." && pwd)/deploy/scripts/deploy-lib.sh"
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' EXIT

PASS=0
FAIL=0
ok() { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

echo "== DA-11 deploy-lib 测试 =="

# ---- 1. 库存在且可 source ----
[ -f "$SCRIPT" ] && ok "库文件存在" || bad "库文件缺失: $SCRIPT"
# shellcheck disable=SC1090
source "$SCRIPT" || { echo "source 失败"; exit 1; }
ok "库可 source"

# ---- 2. 路径映射：空输入 → 空输出 ----
M=$(deploy_map_changes "")
[ "$M" = "" ] && ok "空输入 → 空输出" || bad "空输入输出异常（${M}）"

# ---- 3. 路径映射：仅 tts/voice 不触发 backend ----
M=$(deploy_map_changes "backend/tts-service/app.py")
[ "$M" = "tts" ] && ok "tts-service → tts（不含 backend）" || bad "tts 映射异常（${M}）"
M=$(deploy_map_changes "backend/voice-service/app.py")
[ "$M" = "voice" ] && ok "voice-service → voice（不含 backend）" || bad "voice 映射异常（${M}）"

# ---- 4. 路径映射：backend 根/其他子目录 → backend ----
M=$(deploy_map_changes "backend/pom.xml")
[ "$M" = "backend" ] && ok "backend/ 根文件 → backend" || bad "backend 根映射异常（${M}）"
M=$(deploy_map_changes "backend/counseling-app/src/Main.java")
[ "$M" = "backend" ] && ok "backend 其他子目录 → backend" || bad "backend 子目录映射异常（${M}）"

# ---- 5. 路径映射：backend 混合 tts → backend + tts ----
M=$(deploy_map_changes "backend/counseling-app/src/Main.java
backend/tts-service/app.py")
[ "$M" = "backend tts" ] && ok "backend+tts → 双组件" || bad "backend+tts 映射异常（${M}）"

# ---- 6. 路径映射：三端 → student teacher parent ----
M=$(deploy_map_changes "frontend/student-h5/src/App.tsx
frontend/teacher-web/src/App.tsx
frontend/parent-h5/src/App.tsx")
[ "$M" = "student teacher parent" ] && ok "三端映射正确" || bad "三端映射异常（${M}）"

# ---- 7. 路径映射：deploy.sh / deploy/ 变更 → 全量 ----
M=$(deploy_map_changes "deploy.sh")
[ "$M" = "backend tts voice" ] && ok "deploy.sh 变更 → backend+tts+voice" || bad "deploy.sh 映射异常（${M}）"
M=$(deploy_map_changes "deploy/docker-compose.yml")
[ "$M" = "backend tts voice" ] && ok "deploy/ 变更 → backend+tts+voice" || bad "deploy/ 映射异常（${M}）"

# ---- 8. 路径映射：deploy 变更与既有组件合并去重 ----
M=$(deploy_map_changes "deploy/docker-compose.yml
backend/tts-service/app.py
frontend/student-h5/src/App.tsx")
[ "$M" = "backend student tts voice" ] && ok "全量合并去重且顺序稳定" || bad "合并去重异常（${M}）"

# ---- 9. 路径映射：无关目录（design/ 等）不触发 ----
M=$(deploy_map_changes "design/03_系统整体技术架构设计.md
README.md")
[ "$M" = "" ] && ok "无关目录 → 空输出" || bad "无关目录误触发（${M}）"

# ---- 10. retry：首次成功 ----
if retry 3 0 true; then
  ok "首次成功直接返回 0"
else
  bad "首次成功却返回失败"
fi

# ---- 11. retry：失败后成功（递增退避 1s） ----
MOCK="$TEST_ROOT/mock-fail-times.sh"
cat > "$MOCK" <<'EOF'
#!/usr/bin/env bash
# $1 = 前 N 次失败，$2 = 计数器文件
FAIL_TIMES="$1"
COUNTER="$2"
N=0
[ -f "$COUNTER" ] && N=$(cat "$COUNTER")
N=$((N + 1))
echo "$N" > "$COUNTER"
[ "$N" -gt "$FAIL_TIMES" ]
EOF
chmod +x "$MOCK"
COUNTER="$TEST_ROOT/counter1"
echo 0 > "$COUNTER"
WARN=$( { retry 3 0 "$MOCK" 1 "$COUNTER"; } 2>&1 >/dev/null )
RC=$?
[ "$RC" -eq 0 ] && ok "失败 1 次后重试成功 → rc=0" || bad "重试成功路径 rc=${RC}"
[ "$(cat "$COUNTER")" = "2" ] && ok "实际执行 2 次" || bad "执行次数异常（$(cat "$COUNTER")）"
echo "$WARN" | grep -q "第 1 次失败，1s 后重试" && ok "退避警告：第 1 次失败 1s 后重试" || bad "退避警告缺失（${WARN}）"

# ---- 12. retry：全部失败（递增退避 1s+2s） ----
COUNTER2="$TEST_ROOT/counter2"
echo 0 > "$COUNTER2"
if WARN2=$( { retry 3 0 "$MOCK" 9 "$COUNTER2"; } 2>&1 >/dev/null ); then
  RC2=0
else
  RC2=$?
fi
[ "$RC2" -ne 0 ] && ok "3 次全失败 → rc≠0" || bad "全失败路径 rc=${RC2}"
[ "$(cat "$COUNTER2")" = "3" ] && ok "恰好执行 3 次" || bad "执行次数异常（$(cat "$COUNTER2")）"
echo "$WARN2" | grep -q "重试 3 次后仍失败" && ok "失败总结信息存在" || bad "失败总结缺失（${WARN2}）"

# ---- 13. retry：固定间隔 + 传参透传 ----
COUNTER3="$TEST_ROOT/counter3"
echo 0 > "$COUNTER3"
# sleep 0 快速路径：固定间隔 0 且首次失败 → 退避仍为 1s（与 delay=0 语义一致）
WARN3=$( { retry 2 0 "$MOCK" 9 "$COUNTER3"; } 2>&1 >/dev/null ) || true
[ "$(cat "$COUNTER3")" = "2" ] && ok "固定间隔路径执行次数正确" || bad "固定间隔次数异常（$(cat "$COUNTER3")）"
echo "$WARN3" | grep -q "第 1 次失败，1s 后重试" && ok "间隔 0 → 递增退避生效" || bad "退避未生效（${WARN3}）"

# ---- 14. nginx_build_cmd：命令构建 ----
CMD=$(nginx_build_cmd "student:/guju/mindsafe/frontend/student-h5/dist/ teacher:/guju/mindsafe/frontend/teacher-web/dist/")
echo "$CMD" | grep -q "grep -q '/guju/mindsafe/frontend/student-h5/dist/' /etc/nginx/nginx.conf" \
  && ok "命令含 student 路径 grep" || bad "student 命令缺失（${CMD}）"
echo "$CMD" | grep -q "echo 'OK:student'" && ok "命令含 OK:student 标记" || bad "OK 标记缺失（${CMD}）"
echo "$CMD" | grep -q "echo 'MISS:teacher'" && ok "命令含 MISS:teacher 标记" || bad "MISS 标记缺失（${CMD}）"

# ---- 15. nginx_parse_out：全 OK → rc=0 ----
OUT=$(nginx_parse_out "OK:student
OK:teacher")
RC3=$?
[ "$RC3" -eq 0 ] && ok "全 OK → rc=0" || bad "全 OK rc=${RC3}"
echo "$OUT" | grep -q "student 路径指向校验通过" && ok "输出含 student 通过行" || bad "student 通过行缺失（${OUT}）"

# ---- 16. nginx_parse_out：含 MISS → rc≠0 + 提示 ----
if OUT2=$(nginx_parse_out "OK:student
MISS:parent"); then
  RC4=0
else
  RC4=$?
fi
[ "$RC4" -ne 0 ] && ok "含 MISS → rc≠0" || bad "含 MISS rc=${RC4}"
echo "$OUT2" | grep -q "parent" && ok "输出含 parent 未指向提示" || bad "MISS 提示缺失（${OUT2}）"
echo "$OUT2" | grep -q "前端可能仍在服务旧目录" && ok "含旧目录警示" || bad "警示缺失（${OUT2}）"

echo ""
echo "deploy-lib 测试: $PASS 通过 / $FAIL 失败"
[ "$FAIL" -eq 0 ]
