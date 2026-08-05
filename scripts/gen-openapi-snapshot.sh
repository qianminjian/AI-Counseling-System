#!/usr/bin/env bash
# ============================================================
# TEST-006/M2: OpenAPI 契约快照生成脚本
# 从运行中的后端拉取 /api-docs（springdoc 2.7.0），精简为前端
# 契约测试资产（仅保留 paths + components.schemas + info 摘要）
#
# 用法：bash scripts/gen-openapi-snapshot.sh [BASE_URL] [-o <输出路径>]
#   默认 BASE_URL=http://localhost:8080
#   默认输出 frontend/student-h5/src/__contract__/openapi.json
# 前置：后端已启动（springdoc 输出 /api-docs 可用）
# 说明：python3 校验与精简（不依赖 jq，与 S5 prepare-funasr 一致）
# ============================================================
set -euo pipefail

BASE_URL="http://localhost:8080"
OUTPUT="frontend/student-h5/src/__contract__/openapi.json"

# 参数解析：-o <path> 选项 + 位置参数 BASE_URL（互不依赖顺序）
while [[ $# -gt 0 ]]; do
    case "$1" in
        -o)
            OUTPUT="${2:?用法: -o 需要输出路径}"
            shift 2
            ;;
        *)
            BASE_URL="$1"
            shift
            ;;
    esac
done

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# 绝对路径直接使用，相对路径基于仓库根解析
case "$OUTPUT" in
    /*) OUTPUT_PATH="$OUTPUT" ;;
    *)  OUTPUT_PATH="$REPO_ROOT/$OUTPUT" ;;
esac

# 1) 拉取 OpenAPI 文档（-sf：静默 + 非 2xx 即失败；15s 超时防挂起）
RESPONSE="$(curl -sf --max-time 15 "$BASE_URL/api-docs" 2>/dev/null || true)"
if [[ -z "$RESPONSE" ]]; then
    echo "ERROR: 无法从 $BASE_URL/api-docs 获取 OpenAPI 文档（curl 失败或返回非 2xx）" >&2
    exit 1
fi

# 2) python3 校验 JSON 合法性 + 精简为快照（paths / schemas / info 摘要）
export SNAP_JSON="$RESPONSE"
export SNAP_OUTPUT="$OUTPUT_PATH"
python3 - <<'PYEOF'
import json
import os
import sys

try:
    doc = json.loads(os.environ["SNAP_JSON"])
except json.JSONDecodeError as exc:
    print(f"ERROR: OpenAPI 响应不是合法 JSON: {exc}", file=sys.stderr)
    sys.exit(1)

paths = doc.get("paths")
if not isinstance(paths, dict):
    print("ERROR: OpenAPI 响应缺少 paths 字段", file=sys.stderr)
    sys.exit(1)

snapshot = {
    "info": {
        "title": doc.get("info", {}).get("title", ""),
        "version": doc.get("info", {}).get("version", ""),
    },
    "paths": paths,
    "components": {"schemas": doc.get("components", {}).get("schemas", {})},
}

output = os.environ["SNAP_OUTPUT"]
os.makedirs(os.path.dirname(output), exist_ok=True)
with open(output, "w", encoding="utf-8") as fh:
    json.dump(snapshot, fh, ensure_ascii=False, indent=2)

print(f"OK: 快照已生成 -> {output} "
      f"(paths={len(snapshot['paths'])}, schemas={len(snapshot['components']['schemas'])})")
PYEOF
