#!/bin/bash
# MindSafe 服务器初始化脚本
# 适用于阿里云 ECS / 腾讯云轻量（Ubuntu 22.04/24.04 x86_64）
# 用法：cd deploy && ./setup-server.sh
# AUD-033：stdin 管道方式（ssh root@ip < setup-server.sh）已废弃——$0=bash 导致 SCRIPT_DIR
#          解析错误、配置复制被静默跳过；现改为 fail-fast 阻断 + 用法提示

set -e

echo "===== MindSafe Server Setup ====="

# 1. 安装 Docker（使用阿里云镜像源加速）
if ! command -v docker &> /dev/null; then
    echo "[1/6] Installing Docker (aliyun mirror)..."
    apt-get update -qq
    apt-get install -y -qq ca-certificates curl gnupg
    install -m 0755 -d /etc/apt/keyrings
    # 使用阿里云 Docker CE 源（国内快）
    curl -fsSL https://mirrors.aliyun.com/docker-ce/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    chmod a+r /etc/apt/keyrings/docker.gpg
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://mirrors.aliyun.com/docker-ce/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" > /etc/apt/sources.list.d/docker.list
    apt-get update -qq
    apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-compose-plugin
    usermod -aG docker $USER 2>/dev/null || true
    echo "  Docker installed ✓"
else
    echo "[1/6] Docker already installed ✓"
fi

# 2. 配置 Docker 镜像加速（拉取基础镜像用）
echo "[2/6] Configuring Docker registry mirror..."
mkdir -p /etc/docker
if [ ! -f /etc/docker/daemon.json ] || ! grep -q "registry-mirrors" /etc/docker/daemon.json; then
    cat > /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": [
    "https://docker.1ms.run",
    "https://docker.xuanyuan.me"
  ],
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
EOF
    systemctl daemon-reload
    systemctl restart docker
    echo "  Registry mirror configured ✓"
else
    echo "  daemon.json already exists, skipping ✓"
fi

# 3. 开启 swap（2C2G 实例兜底）
echo "[3/6] Configuring swap (2GB)..."
if [ ! -f /swapfile ]; then
    fallocate -l 2G /swapfile
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
    # 降低 swappiness，优先用物理内存
    sysctl vm.swappiness=10
    echo 'vm.swappiness=10' >> /etc/sysctl.conf
    echo "  Swap 2GB created ✓"
else
    echo "  Swap already exists ✓"
fi

# 4. 创建部署目录（AUD-002：与 deploy.sh / service-manager.sh / cd.yml 的 $REMOTE_DIR/deploy 路径对齐）
echo "[4/6] Creating /guju/mindsafe/deploy..."
mkdir -p /guju/mindsafe/deploy
mkdir -p /guju/mindsafe/frontend
chown -R $USER:$USER /guju/mindsafe 2>/dev/null || true

# 5. 同步部署文件（AUD-002：完整同步 deploy/ 目录，而非仅 test.yml）
#    服务器约定（与 deploy.sh rsync / cd.yml SSH / service-manager.sh 一致）：
#      compose 文件在 /guju/mindsafe/deploy/，.env 在 /guju/mindsafe/deploy/.env
#      CD 部署执行 cd $REMOTE_DIR/deploy && docker compose -f docker-compose.prod.yml ...
echo "[5/7] Syncing deploy configs..."
# AUD-033：BASH_SOURCE 仅直接执行时可靠（stdin 管道 $0=bash）；
# 找不到 compose 文件即 fail-fast，不再静默跳过
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ ! -f "$SCRIPT_DIR/docker-compose.prod.yml" ]; then
    echo "❌ 未找到 $SCRIPT_DIR/docker-compose.prod.yml——脚本必须从 deploy/ 目录执行：cd deploy && ./setup-server.sh"
    echo "   （不要使用 ssh < setup-server.sh 管道方式，AUD-033 已废弃）"
    exit 1
fi
cp "$SCRIPT_DIR"/docker-compose*.yml /guju/mindsafe/deploy/
cp -r "$SCRIPT_DIR/nginx" "$SCRIPT_DIR/init" "$SCRIPT_DIR/monitoring" "$SCRIPT_DIR/scripts" /guju/mindsafe/deploy/ 2>/dev/null || true
cp "$SCRIPT_DIR/backup.sh" "$SCRIPT_DIR/restore.sh" "$SCRIPT_DIR/backup-common.sh" /guju/mindsafe/deploy/ 2>/dev/null || true
chmod +x /guju/mindsafe/deploy/*.sh 2>/dev/null || true
if [ ! -f /guju/mindsafe/deploy/.env ]; then
    cp "$SCRIPT_DIR/.env.example" /guju/mindsafe/deploy/.env
    echo "  .env created from .env.example. Edit /guju/mindsafe/deploy/.env with your secrets!"
else
    echo "  .env already exists, skipped"
fi
echo "  Configs synced to /guju/mindsafe/deploy/"

# 6. 备份 cron 自动接线（AUD-032：此前手册称 02:00 daily/weekly/monthly 分层备份，
#    但 setup-server.sh 从不配置 cron——现已幂等写入；backup.sh 内部按日/周/月分层保留，
#    cron 仅需每天 02:00 触发一次；恢复演练指引见 DEPLOY-GUIDE.md「备份与恢复」）
#    DC-002：cron 必须指向 deploy/ 整目录内的 backup.sh（该目录由本脚本第 5 步完整同步，
#    恒存在）；历史故障是 cron 指向 deploy/ 目录之外的备份脚本路径——该路径从不被
#    任何脚本创建，导致每日备份静默失败；写入前 fail-fast 校验脚本文件存在。
echo "[6/6] Configuring backup cron..."
# DC-002 fail-fast：备份脚本缺失时拒绝写入 cron，避免 cron 指向不存在的文件（历史故障）
[ -f "$SCRIPT_DIR/backup.sh" ] || { echo "❌ 未找到 $SCRIPT_DIR/backup.sh——备份脚本缺失，拒绝写入 cron"; exit 1; }
CRON_LINE="0 2 * * * /guju/mindsafe/deploy/backup.sh >> /guju/mindsafe/logs/backup.log 2>&1"
if crontab -l 2>/dev/null | grep -q 'backup.sh'; then
    echo "  backup cron 已存在，跳过（幂等）"
else
    ( crontab -l 2>/dev/null; echo "$CRON_LINE" ) | crontab -
    echo "  backup cron 已写入: $CRON_LINE"
fi

# 7. 收尾（D6：取消 CD 后（DOC-063）镜像在服务器本地构建（deploy.sh → docker compose build），GHCR login / GitHub Secrets 已无用）
echo "[7/7] Setup complete"
echo ""
echo "===== Setup Complete ====="
echo ""
echo "Next steps:"
echo "  1. Edit /guju/mindsafe/deploy/.env (DB_PASSWORD, REDIS_PASSWORD, LLM_API_KEY, JWT_SECRET)"
echo "  2. cd /guju/mindsafe/deploy && docker compose -f docker-compose.prod.yml up -d"
echo ""
echo "阿里云注意事项:"
echo "  - 安全组需开放 80/443 端口（ECS → 安全组 → 配置规则）"
echo "  - 域名需 ICP 备案（测试阶段直接用 IP 访问）"
echo "  - 经济型 e 实例 2C2G 已开启 swap 兜底"
