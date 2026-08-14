#!/usr/bin/env bash
# ============================================================
# 生成字段加密密钥（B-05 / COMP-008，2026-08-14）
#
# 用法：
#   ./scripts/gen-encryption-key.sh                # 生成 32 字节随机密钥（base64）
#   ./scripts/gen-encryption-key.sh --write-env    # 生成并写入 .env（MINDSAFE_ENCRYPTION_KEY）
#
# 输出：
#   MINDSAFE_ENCRYPTION_KEY=<base64>  放入服务器 .env 后置 ENCRYPTION_ENABLED=true
#
# 密钥治理（frozen/60 COMP-008 清单）：
#   1. 生成：本脚本（CSPRNG，openssl rand 32 字节）
#   2. 备份：离线保存（密码管理器/保险柜），丢失即数据不可解密
#   3. 权限：服务器 .env 文件 chmod 600
#   4. 轮换：新密钥写入 MINDSAFE_ENCRYPTION_KEY + 旧密钥按
#      MINDSAFE_ENCRYPTION_PREVIOUS_KEYS=<version>:<base64>,... 保留解密能力
# ============================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"

key="$(openssl rand -base64 32 | tr -d '\n')"

echo "=== 字段加密密钥生成（B-05/COMP-008）==="
echo "MINDSAFE_ENCRYPTION_KEY=${key}"
echo ""
echo "启用步骤："
echo "  1. 将上述密钥写入服务器 .env（MINDSAFE_ENCRYPTION_KEY=<key>）并 chmod 600 .env"
echo "  2. .env 追加 ENCRYPTION_ENABLED=true"
echo "  3. 部署重启（首次启用启动时自动执行存量明文回填，日志见 EncryptionBackfillRunner）"
echo "  4. 离线备份密钥（丢失即无法解密）"
echo ""

if [ "${1:-}" = "--write-env" ]; then
    if [ -f "$ENV_FILE" ] && grep -q "MINDSAFE_ENCRYPTION_KEY=" "$ENV_FILE"; then
        sed -i '' "s|^MINDSAFE_ENCRYPTION_KEY=.*|MINDSAFE_ENCRYPTION_KEY=${key}|" "$ENV_FILE"
    else
        printf "\n# 字段加密（B-05/COMP-008；启用需密钥治理见 frozen/60）\nMINDSAFE_ENCRYPTION_KEY=%s\n" "$key" >> "$ENV_FILE"
    fi
    echo "已写入 $ENV_FILE"
fi
