#!/bin/bash
# MindSafe 学校初始化工具
# 用法：./deploy/init-school.sh <学校名称> <学校编码> <管理员姓名> [管理员密码]
# 示例：./deploy/init-school.sh "南宁市青秀区实验小学" "NN-QX-001" "张老师" "Init@2026"
#
# 功能：
#   1. 创建租户（tenants）
#   2. 创建学校（schools）
#   3. 创建管理员账号（users + user_roles）
#   4. 生成 10 个初始邀请码（trial_invite_codes）
#
# 前置条件：
#   - 服务器 PostgreSQL 可达（通过 docker exec）
#   - 已部署 MindSafe 后端（Flyway 迁移已完成）
set -euo pipefail

SERVER="root@116.8.109.229"
CONTAINER="mindsafe-pg"
DB_NAME="mindsafe"
DB_USER="mindsafe"

# ===== 参数校验 =====
if [ $# -lt 3 ]; then
  echo "用法：$0 <学校名称> <学校编码> <管理员姓名> [管理员密码]"
  echo "示例：$0 \"南宁市青秀区实验小学\" \"NN-QX-001\" \"张老师\" \"Init@2026\""
  exit 1
fi

SCHOOL_NAME="$1"
SCHOOL_CODE="$2"
ADMIN_NAME="$3"
ADMIN_PASSWORD="${4:-Init@2026}"

echo "🏫 学校初始化"
echo "   学校名称：$SCHOOL_NAME"
echo "   学校编码：$SCHOOL_CODE"
echo "   管理员：$ADMIN_NAME"
echo ""

# ===== 生成 UUID =====
TENANT_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
SCHOOL_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
ADMIN_USER_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
ROLE_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
USER_ROLE_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')

echo "📋 生成 ID："
echo "   租户ID：$TENANT_ID"
echo "   学校ID：$SCHOOL_ID"
echo "   管理员ID：$ADMIN_USER_ID"
echo ""

# ===== 生成 BCrypt 密码哈希 =====
# 使用 Python bcrypt（服务器通常有 python3）
PASSWORD_HASH=$(python3 -c "
import hashlib, base64, os
# 简化：使用 PostgreSQL 的 crypt 函数代替
print('USE_PG_CRYPT')
" 2>/dev/null || echo "USE_PG_CRYPT")

# ===== 构建 SQL =====
read -r -d '' SQL << 'EOSQL' || true
-- 1. 创建租户
INSERT INTO tenants (tenant_id, tenant_code, tenant_name, status)
VALUES (
  :'TENANT_ID',
  :'SCHOOL_CODE',
  :'SCHOOL_NAME',
  'active'
) ON CONFLICT (tenant_code) DO UPDATE SET tenant_name = :'SCHOOL_NAME', status = 'active';

-- 2. 创建学校
INSERT INTO schools (school_id, tenant_id, school_code, school_name, edu_stage, status)
VALUES (
  :'SCHOOL_ID',
  :'TENANT_ID',
  :'SCHOOL_CODE',
  :'SCHOOL_NAME',
  'primary',
  'active'
) ON CONFLICT (tenant_id, school_code) DO UPDATE SET school_name = :'SCHOOL_NAME', status = 'active';

-- 3. 创建管理员用户（密码使用 pgcrypto crypt）
INSERT INTO tenant_template.users (user_id, tenant_id, school_id, user_type, pseudonym, status, password_hash, must_change_password)
VALUES (
  :'ADMIN_USER_ID',
  :'TENANT_ID',
  :'SCHOOL_ID',
  'school_admin',
  :'ADMIN_NAME',
  'active',
  crypt(:'ADMIN_PASSWORD', gen_salt('bf', 10)),
  true
) ON CONFLICT (user_id) DO UPDATE SET pseudonym = :'ADMIN_NAME', status = 'active';

-- 4. 创建管理员角色
INSERT INTO tenant_template.roles (role_id, tenant_id, role_code, role_name, scope_level, permission_set, is_system)
VALUES (
  :'ROLE_ID',
  :'TENANT_ID',
  'school_admin',
  '学校管理员',
  'school',
  '["user.manage", "school.settings", "report.export", "audit.view", "alert.view", "case.manage"]',
  true
) ON CONFLICT (tenant_id, role_code) DO NOTHING;

-- 5. 绑定用户角色
INSERT INTO tenant_template.user_roles (user_role_id, tenant_id, user_id, role_id, school_id)
SELECT :'USER_ROLE_ID', :'TENANT_ID', :'ADMIN_USER_ID', r.role_id, :'SCHOOL_ID'
FROM tenant_template.roles r
WHERE r.tenant_id = :'TENANT_ID' AND r.role_code = 'school_admin'
ON CONFLICT (user_role_id) DO NOTHING;

-- 6. 生成 10 个初始邀请码
INSERT INTO tenant_template.trial_invite_codes (code_id, tenant_id, code, max_uses, status, batch_id, expires_at)
SELECT
  uuid_generate_v4(),
  :'TENANT_ID',
  upper(substring(md5(random()::text || i::text) from 1 for 8)),
  1,
  'active',
  'INIT-' || to_char(now(), 'YYYYMMDD'),
  now() + interval '90 days'
FROM generate_series(1, 10) AS i;

-- 7. 输出结果
SELECT '=== 初始化完成 ===' AS result;
SELECT '租户: ' || tenant_code || ' (' || tenant_name || ')' AS info FROM tenants WHERE tenant_id = :'TENANT_ID';
SELECT '学校: ' || school_code || ' (' || school_name || ')' AS info FROM schools WHERE school_id = :'SCHOOL_ID';
SELECT '管理员: ' || pseudonym || ' (首次登录需改密)' AS info FROM tenant_template.users WHERE user_id = :'ADMIN_USER_ID';
SELECT '邀请码: ' || string_agg(code, ', ' ORDER BY created_at) AS codes
FROM tenant_template.trial_invite_codes
WHERE tenant_id = :'TENANT_ID' AND batch_id = 'INIT-' || to_char(now(), 'YYYYMMDD');
EOSQL

# ===== 执行 SQL =====
echo "🚀 执行初始化..."
ssh "$SERVER" "docker exec -i $CONTAINER psql -U $DB_USER -d $DB_NAME" << EOF
\\set TENANT_ID '$TENANT_ID'
\\set SCHOOL_ID '$SCHOOL_ID'
\\set SCHOOL_CODE '$SCHOOL_CODE'
\\set SCHOOL_NAME '$SCHOOL_NAME'
\\set ADMIN_USER_ID '$ADMIN_USER_ID'
\\set ADMIN_NAME '$ADMIN_NAME'
\\set ADMIN_PASSWORD '$ADMIN_PASSWORD'
\\set ROLE_ID '$ROLE_ID'
\\set USER_ROLE_ID '$USER_ROLE_ID'
$SQL
EOF

echo ""
echo "🎉 学校初始化完成！"
echo ""
echo "📌 后续步骤："
echo "   1. 将邀请码分发给教师"
echo "   2. 教师使用邀请码注册后，通过管理后台批量导入学生"
echo "   3. 管理员首次登录后修改默认密码"
