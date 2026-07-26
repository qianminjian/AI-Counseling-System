#!/bin/bash
# MindSafe 服务器初始化脚本
# 适用于阿里云 ECS / 腾讯云轻量（Ubuntu 22.04/24.04 x86_64）
# 用法：ssh root@<your-ip> < setup-server.sh

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

# 4. 创建部署目录
echo "[4/6] Creating /opt/mindsafe..."
mkdir -p /opt/mindsafe
chown $USER:$USER /opt/mindsafe 2>/dev/null || true

# 5. 复制部署文件（假设从 git clone 或 scp 过来）
echo "[5/6] Copying deploy configs..."
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$SCRIPT_DIR/docker-compose.test.yml" ]; then
    cp "$SCRIPT_DIR/docker-compose.test.yml" /opt/mindsafe/
    cp -r "$SCRIPT_DIR/nginx" /opt/mindsafe/ 2>/dev/null || true
    cp "$SCRIPT_DIR/.env.example" /opt/mindsafe/.env 2>/dev/null || true
    echo "  Configs copied. Edit /opt/mindsafe/.env with your secrets!"
else
    echo "  ⚠️  Run this script from the deploy/ directory"
fi

# 6. GHCR 登录提示
echo "[6/6] Docker GHCR login..."
echo "  Run manually: docker login ghcr.io -u <your-github-username>"
echo "  Use a GitHub Personal Access Token (read:packages) as password"

echo ""
echo "===== Setup Complete ====="
echo ""
echo "Next steps:"
echo "  1. Edit /opt/mindsafe/.env (DB_PASSWORD, REDIS_PASSWORD, LLM_API_KEY)"
echo "  2. docker login ghcr.io -u <github-username>"
echo "  3. cd /opt/mindsafe && docker compose -f docker-compose.test.yml up -d"
echo "  4. Configure GitHub Secrets: DEPLOY_HOST, DEPLOY_USER, DEPLOY_SSH_KEY"
echo ""
echo "阿里云注意事项:"
echo "  - 安全组需开放 80/443 端口（ECS → 安全组 → 配置规则）"
echo "  - 域名需 ICP 备案（测试阶段直接用 IP 访问）"
echo "  - 经济型 e 实例 2C2G 已开启 swap 兜底"
