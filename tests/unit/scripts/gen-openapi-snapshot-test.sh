#!/usr/bin/env bash
# ============================================================
# TEST-006/M2: scripts/gen-openapi-snapshot.sh 行为测试（TDD）
# 验证：默认 BASE_URL / curl 失败 fail-fast / 非 JSON / 缺 paths /
#       正常生成精简快照 / -o 自定义路径 / 摘要输出格式
# 用法：bash tests/unit/scripts/gen-openapi-snapshot-test.sh
# ============================================================
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")/../../.." && pwd)/scripts/gen-openapi-snapshot.sh"
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' EXIT

PASS=0
FAIL=0
ok() { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# fake curl：FAKE_CURL_EXIT 控制退出码，FAKE_CURL_BODY 控制 stdout（避免真实网络）
BIN="$TEST_ROOT/bin"
mkdir -p "$BIN"
cat > "$BIN/curl" <<'EOF'
#!/bin/bash
printf '%s' "${FAKE_CURL_BODY:-}"
exit "${FAKE_CURL_EXIT:-0}"
EOF
chmod +x "$BIN/curl"

OUT="$TEST_ROOT/openapi.json"
VALID_BODY='{"openapi":"3.0.1","info":{"title":"MindSafe API","version":"0.1.0"},"servers":[{"url":"http://localhost:8080"}],"paths":{"/api/v1/toolbox":{"get":{"responses":{"200":{"description":"ok"}}}}},"components":{"schemas":{"ApiResponseVoid":{"type":"object"}}}}'

echo "== TEST-006/M2 gen-openapi-snapshot 测试 =="

# ---- 1. 脚本存在且可执行 ----
if [ -x "$SCRIPT" ]; then
    ok "脚本存在且可执行"
else
    bad "脚本缺失或不可执行: $SCRIPT"
fi

# ---- 2. 默认 BASE_URL=http://localhost:8080 ----
if grep -q 'localhost:8080' "$SCRIPT"; then
    ok "默认 BASE_URL=http://localhost:8080"
else
    bad "缺少默认 BASE_URL"
fi

# ---- 3. curl 失败 → 非零退出 ----
if PATH="$BIN:$PATH" FAKE_CURL_EXIT=22 FAKE_CURL_BODY= bash "$SCRIPT" http://fake:1 -o "$OUT" >/dev/null 2>&1; then
    bad "curl 失败时未退出非 0"
else
    ok "curl 失败 → 非零退出"
fi

# ---- 4. curl 失败 → 不产出文件 ----
if [ ! -f "$OUT" ]; then
    ok "curl 失败时未产出文件"
else
    bad "curl 失败时仍产出文件"
fi

# ---- 5. 非 JSON 响应 → 非零退出 ----
if PATH="$BIN:$PATH" FAKE_CURL_EXIT=0 FAKE_CURL_BODY='not-json' bash "$SCRIPT" http://fake:1 -o "$OUT" >/dev/null 2>&1; then
    bad "非 JSON 响应未退出非 0"
else
    ok "非 JSON 响应 → 非零退出"
fi

# ---- 6. JSON 缺 paths → 非零退出 ----
if PATH="$BIN:$PATH" FAKE_CURL_EXIT=0 FAKE_CURL_BODY='{"info":{"title":"x"}}' bash "$SCRIPT" http://fake:1 -o "$OUT" >/dev/null 2>&1; then
    bad "缺 paths 未退出非 0"
else
    ok "JSON 缺 paths → 非零退出"
fi

# ---- 7. 正常响应 → 产出快照文件 ----
PATH="$BIN:$PATH" FAKE_CURL_EXIT=0 FAKE_CURL_BODY="$VALID_BODY" bash "$SCRIPT" http://fake:1 -o "$OUT" >/dev/null 2>&1
if [ -f "$OUT" ]; then
    ok "正常响应 → 产出快照文件"
else
    bad "正常响应未产出文件"
fi

# ---- 8. 快照结构精简（仅 info/paths/components.schemas） ----
if python3 - "$OUT" <<'PYEOF' && ok "快照仅含 info/paths/components.schemas 且内容保留"
import json
import sys
doc = json.load(open(sys.argv[1], encoding="utf-8"))
assert set(doc.keys()) == {"info", "paths", "components"}, f"多余键: {doc.keys()}"
assert doc["paths"]["/api/v1/toolbox"]["get"]["responses"]["200"]["description"] == "ok"
assert doc["components"]["schemas"]["ApiResponseVoid"]["type"] == "object"
assert doc["info"]["title"] == "MindSafe API"
PYEOF
then
    :
else
    bad "快照结构或内容不符"
fi

# ---- 9. -o 自定义路径生效 ----
CUSTOM="$TEST_ROOT/sub/dir/custom.json"
PATH="$BIN:$PATH" FAKE_CURL_EXIT=0 FAKE_CURL_BODY="$VALID_BODY" bash "$SCRIPT" http://fake:1 -o "$CUSTOM" >/dev/null 2>&1
if [ -f "$CUSTOM" ]; then
    ok "-o 自定义输出路径生效（含嵌套目录创建）"
else
    bad "-o 自定义输出路径未生效"
fi

# ---- 10. 摘要输出格式（paths 数 / schemas 数） ----
SUMMARY="$(PATH="$BIN:$PATH" FAKE_CURL_EXIT=0 FAKE_CURL_BODY="$VALID_BODY" bash "$SCRIPT" http://fake:1 -o "$OUT")"
if echo "$SUMMARY" | grep -qE 'OK: .*\(paths=1, schemas=1\)'; then
    ok "摘要输出格式正确（paths=1, schemas=1）"
else
    bad "摘要输出格式不符: $SUMMARY"
fi

# ---- 11. 空 schemas 容错（components 缺失时视为空对象而非失败） ----
MIN_BODY='{"info":{"title":"x","version":"v"},"paths":{}}'
PATH="$BIN:$PATH" FAKE_CURL_EXIT=0 FAKE_CURL_BODY="$MIN_BODY" bash "$SCRIPT" http://fake:1 -o "$OUT" >/dev/null 2>&1
if python3 -c "import json,sys; d=json.load(open('$OUT')); assert d['components']['schemas'] == {}" 2>/dev/null; then
    ok "components 缺失时容错为空 schemas"
else
    bad "components 缺失时处理异常"
fi

echo ""
echo "结果: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
