#!/bin/bash
# MindSafe 学校初始化工具
# 用法：./deploy/init-school.sh <学校名称> <学校编码> <管理员姓名> [管理员密码]
# 示例：./deploy/init-school.sh "南宁市青秀区实验小学" "NN-QX-001" "张老师"
#
# 环境变量：
#   MINDSAFE_SERVER  - SSH 目标（必填，如 root@10.0.1.50；不设默认值避免误连生产）
#   MINDSAFE_PG_CONTAINER - PostgreSQL 容器名（默认 mindsafe-pg）
#   MINDSAFE_DB_NAME - 数据库名（默认 mindsafe）
#   MINDSAFE_DB_USER - 数据库用户（默认 mindsafe）
#
# SIT 示例：
#   MINDSAFE_SERVER=root@10.0.1.50 ./deploy/init-school.sh "SIT测试学校" "SIT-001" "测试管理员"
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

SERVER="${MINDSAFE_SERVER:-}"
if [ -z "${SERVER}" ]; then
  echo "ERROR: 必须通过 MINDSAFE_SERVER 指定 SSH 目标（如 MINDSAFE_SERVER=root@10.0.1.50），不设默认值避免误连生产"
  exit 1
fi
CONTAINER="${MINDSAFE_PG_CONTAINER:-mindsafe-pg}"
DB_NAME="${MINDSAFE_DB_NAME:-mindsafe}"
DB_USER="${MINDSAFE_DB_USER:-mindsafe}"

# ===== 参数校验 =====
if [ $# -lt 3 ]; then
  echo "用法：$0 <学校名称> <学校编码> <管理员姓名> [管理员密码]"
  echo "示例：MINDSAFE_SERVER=root@10.0.1.50 $0 \"南宁市青秀区实验小学\" \"NN-QX-001\" \"张老师\""
  exit 1
fi

SCHOOL_NAME="$1"
SCHOOL_CODE="$2"
ADMIN_NAME="$3"
# 未指定密码时随机生成（首次登录强制改密，must_change_password=true）
if [ $# -ge 4 ]; then
  ADMIN_PASSWORD="$4"
else
  ADMIN_PASSWORD=$(openssl rand -base64 12)
  echo "🔑 未指定管理员密码，已随机生成（请妥善保管，首次登录需改密）：${ADMIN_PASSWORD}"
fi

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
