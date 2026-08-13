#!/bin/bash
# gen-schema-snapshot.sh — 从数据库生成 01_schema.sql 快照（doing/92 R-023）
# 背景：手工快照必然过期（原 01_schema.sql 停在 V29，Flyway 已 V45——灾备重建误导）；
# 本脚本以 Flyway 迁移后的数据库为源生成权威快照，供灾备参照。
# 用法：数据库连接环境变量（与 ci.yml postgres service 对齐）
#   PGHOST/PGPORT/PGDATABASE/PGUSER 或 --url 参数
# CI 挂载：ci.yml 后端 job 中生成 + diff 门禁（防再次过期）。
set -euo pipefail

OUT="${1:-backend/scripts/sql/01_schema.sql}"
PGURL="${PGURL:-jdbc:postgresql://localhost:5432/mindsafe_test}"
# 用户参数化：默认 mindsafe（本地/生产），CI 传 PGUSER=test（postgres service 用户）
PGUSER="${PGUSER:-mindsafe}"

# jdbc:postgresql://host:port/db → psql 参数
HOST=$(echo "$PGURL" | sed -E 's|jdbc:postgresql://([^:/]+).*|\1|')
PORT=$(echo "$PGURL" | sed -E 's|jdbc:postgresql://[^:]+:([0-9]+)/.*|\1|')
DB=$(echo "$PGURL" | sed -E 's|jdbc:postgresql://[^/]+/(.*)|\1|')

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

echo "-- 01_schema.sql 快照（doing/92 R-023：由 gen-schema-snapshot.sh 生成，勿手工编辑）
-- 生成时间: $(date -u +%Y-%m-%dT%H:%M:%SZ)
-- 来源: Flyway 迁移后数据库 ${DB}（V1-V45+）
-- 用途: 灾备重建参照（权威源仍为 Flyway migration/）" > "$TMP"

PGPASSWORD="${PGPASSWORD:-mindsafe}" pg_dump -h "$HOST" -p "$PORT" -U "$PGUSER" -d "$DB" \
  --schema-only --no-owner --no-privileges \
  | grep -vE '^--$|^-- Dumped|^SET |^SELECT pg_catalog|^\\restrict|^\\unrestrict|^-- Name: (tenant_template|public)\\.(schema_migrations|flyway)' \
  >> "$TMP"

# 表头 3 行含动态时间戳/库名，diff 排除（只比 schema 内容）
if diff -q <(tail -n +4 "$TMP") <(tail -n +4 "$OUT") >/dev/null 2>&1; then
  echo "✅ 01_schema.sql 快照与数据库一致"
else
  if [ "${CI:-}" = "true" ]; then
    echo "❌ 01_schema.sql 与数据库不一致（新增迁移后未重新生成快照）——请运行 backend/scripts/gen-schema-snapshot.sh"
    diff <(tail -n +4 "$TMP") <(tail -n +4 "$OUT") | head -20
    exit 1
  fi
  cp "$TMP" "$OUT"
  echo "✅ 01_schema.sql 已重新生成（$(wc -l < "$OUT") 行）"
fi
