# MindSafe 云部署 + CI/CD 方案

> 目标：本机内存不够，将构建和运行全部转移到云端，通过 GitHub Actions 实现自动化发布。

---

## 一、服务器选型

### 推荐：阿里云 ECS 经济型 e 实例

| 资源 | 配额 |
|------|------|
| CPU | 2 vCPU |
| 内存 | 2GB（推荐 4GB） |
| 磁盘 | 40GB ESSD |
| 带宽 | 3Mbps 固定带宽 |
| 公网 IP | 1 个固定 IPv4 |
| 费用 | **99元/年**（新用户首购）|

注册地址：https://www.aliyun.com （支付宝实名认证即可，无需信用卡）

### 为什么选阿里云

- 支付宝实名注册，国内无门槛
- 经济型 e 实例 99元/年（2C2G），续费同价
- 国内访问速度快，学生/教师端体验好
- 后续可平滑升级到 2C4G / 4C8G
- Docker 镜像加速（阿里云 ACR）国内拉取秒级

### 内存分配（测试环境，不含 voice/tts）

| 服务 | 内存 |
|------|------|
| PostgreSQL 16 | ~512MB |
| Redis 7 | ~128MB |
| Spring Boot Backend | ~1GB |
| Nginx | ~64MB |
| 系统 + swap | ~300MB |
| **合计** | **~2GB（2C2G 刚好，4G 宽裕）** |

> 2C2G 建议开启 2GB swap 兜底；2C4G 无需 swap。

### 备选：腾讯云轻量应用服务器

| 资源 | 配额 |
|------|------|
| CPU | 2 vCPU |
| 内存 | 2GB |
| 磁盘 | 50GB SSD |
| 带宽 | 4Mbps |
| 费用 | 约 100元/年（新用户）|

注册地址：https://cloud.tencent.com

---

## 二、CI/CD 架构

```
开发者 git push main
        │
        ▼
┌─────────────────────────┐
│   GitHub Actions CI     │  ← PR 检查：编译 + 构建
└─────────────────────────┘
        │ (merge to main)
        ▼
┌─────────────────────────┐
│  GitHub Actions Deploy  │
│  1. Build Docker images │
│  2. Push to GHCR        │  ← GitHub Container Registry（免费）
│  3. SSH to 阿里云 ECS   │
│  4. docker compose pull │
│  5. docker compose up   │
└─────────────────────────┘
        │
        ▼
┌─────────────────────────┐
│   阿里云 ECS (x86_64)   │  ← 运行环境
│   - PostgreSQL          │
│   - Redis               │
│   - Backend (Spring)    │
│   - Nginx (双域名)      │
└─────────────────────────┘
```

> 镜像仓库说明：默认使用 GHCR（免费、与 GitHub Actions 集成最简）。
> 若国内拉取 GHCR 慢，可换用阿里云容器镜像服务 ACR（个人版免费），见第七节。

---

## 三、一次性设置步骤

### Step 1：购买阿里云 ECS

1. 登录 https://www.aliyun.com → 控制台 → ECS → 创建实例
2. 付费模式：**包年包月**（经济型 e 实例 99元/年）
3. 地域：选离学校近的（如华东1-杭州、华南1-深圳）
4. 实例规格：**ecs.e-c1m1.large**（2C2G）或 **ecs.e-c1m2.large**（2C4G）
5. 镜像：**Ubuntu 22.04 64位**
6. 存储：40GB ESSD Entry
7. 网络：分配公网 IPv4，带宽 3Mbps
8. 安全组：新建，开放 TCP 22/80/443
9. 设置 root 密码或 SSH 密钥对

### Step 2：开放安全组端口

阿里云控制台 → ECS → 安全组 → 配置规则 → 入方向：

| 端口 | 协议 | 授权对象 | 说明 |
|------|------|---------|------|
| 22 | TCP | 0.0.0.0/0 | SSH（建议限制 IP） |
| 80 | TCP | 0.0.0.0/0 | HTTP |
| 443 | TCP | 0.0.0.0/0 | HTTPS |

### Step 3：初始化服务器

```bash
# SSH 登录（用 root 或你创建的用户）
ssh root@<公网IP>

# 克隆项目（或 scp deploy/ 目录过去）
git clone https://github.com/<your-username>/AI-Counseling-System.git
cd AI-Counseling-System/deploy

# 运行初始化脚本
chmod +x setup-server.sh
./setup-server.sh

# 重新登录使 docker 组生效
exit
ssh root@<公网IP>
```

### Step 4：配置环境变量

```bash
cd /opt/mindsafe
vim .env
```

填入：
```env
DB_PASSWORD=<强密码>
REDIS_PASSWORD=<强密码>
LLM_API_KEY=sk-your-deepseek-key
LLM_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-chat
GITHUB_OWNER=<your-github-username>
HTTP_PORT=80
```

### Step 5：GHCR 登录（服务器上）

```bash
# 在 GitHub 生成 Personal Access Token（权限：read:packages）
# Settings → Developer settings → Personal access tokens → Fine-grained tokens
docker login ghcr.io -u <your-github-username>
# 密码填 PAT
```

### Step 6：配置 GitHub Secrets

仓库 → Settings → Secrets and variables → Actions，添加：

| Secret 名称 | 值 |
|-------------|-----|
| `DEPLOY_HOST` | 阿里云 ECS 公网 IP |
| `DEPLOY_USER` | `root`（或你创建的用户名） |
| `DEPLOY_SSH_KEY` | SSH 私钥文件全部内容 |
| `SMOKE_URL` | 部署后的访问地址（如 `http://<公网 IP>`） |

### Step 7：首次部署

```bash
cd /opt/mindsafe
docker compose -f docker-compose.test.yml pull
docker compose -f docker-compose.test.yml run --rm frontend-init
docker compose -f docker-compose.test.yml up -d
```

之后每次 `git push main` 自动触发部署。

> 部署完成后工作流会自动运行冒烟测试（`tests/e2e/smoke-test.sh`），验证健康检查、试用注册、学生对话链路、教师端、安全拦截是否正常。也可本地手动运行：
> ```bash
> BASE_URL=http://<服务器IP> ./tests/e2e/smoke-test.sh
> ```

---

## 四、域名配置（可选）

| 端 | 域名示例 | 说明 |
|----|---------|------|
| 学生端 | `app.yourschool.cn` 或直接用 IP | Nginx default_server |
| 教师端 | `teacher.yourschool.cn` | 修改 nginx/default.conf 中 server_name |

DNS 设置：A 记录 → 阿里云 ECS 公网 IP

> ⚠️ **ICP 备案**：国内服务器绑定域名必须完成 ICP 备案（阿里云有备案入口，约 7-15 个工作日）。
> 测试阶段直接用公网 IP 访问即可，无需备案。

修改教师端域名：
```bash
vim /opt/mindsafe/nginx/default.conf
# 将 server_name teacher.mindsafe.app 改为你的域名
docker compose -f docker-compose.test.yml restart nginx
```

---

## 五、日常开发流程

```bash
# 本地开发（只需编辑器，不需要跑服务）
git checkout -b feature/xxx
# ... 编码 ...
git add . && git commit -m "feat: xxx"
git push origin feature/xxx

# 创建 PR → GitHub Actions CI 自动编译检查
# Merge 到 main → 自动部署到阿里云 ECS
# 浏览器访问 http://<公网IP> 验证
```

本机只需要：代码编辑器 + Git。不需要跑 Docker、不需要大内存。

---

## 六、已创建的文件清单

| 文件 | 用途 |
|------|------|
| `.github/workflows/ci.yml` | PR 检查（后端编译 + 前端构建） |
| `.github/workflows/deploy.yml` | 自动部署（Build → GHCR → SSH） |
| `deploy/docker-compose.test.yml` | 轻量测试环境（不含 voice/tts） |
| `deploy/docker-compose.prod.yml` | 完整生产环境（含 voice/tts） |
| `deploy/nginx/default.conf` | Nginx 双域名反向代理 |
| `deploy/setup-server.sh` | 服务器一键初始化（含 Docker 镜像加速） |
| `deploy/.env.example` | 环境变量模板 |
| `frontend/Dockerfile` | 前端打包镜像（CI 用） |
| `backend/Dockerfile` | 后端多阶段构建镜像 |

---

## 七、镜像加速（可选优化）

### 方案 A：阿里云 Docker 镜像加速（推荐）

setup-server.sh 已自动配置。手动配置方式：

```bash
# 获取你的专属加速地址：阿里云控制台 → 容器镜像服务 → 镜像加速器
sudo mkdir -p /etc/docker
sudo tee /etc/docker/daemon.json <<EOF
{
  "registry-mirrors": ["https://<你的ID>.mirror.aliyuncs.com"]
}
EOF
sudo systemctl daemon-reload
sudo systemctl restart docker
```

### 方案 B：换用阿里云 ACR（GHCR 太慢时）

1. 阿里云控制台 → 容器镜像服务 → 创建命名空间（如 `mindsafe`）
2. 创建镜像仓库：`mindsafe-backend`、`mindsafe-frontend`
3. GitHub Actions deploy.yml 中改 push 目标为 ACR
4. 服务器上 `docker login registry.cn-hangzhou.aliyuncs.com`

---

## 八、注意事项

1. **2C2G 内存紧张**：建议开启 swap（setup-server.sh 已自动配置 2GB swap）
2. **GHCR 私有镜像**：需要在服务器上 `docker login` 才能 pull
3. **x86_64 架构**：阿里云经济型为 x86，CI 构建无需指定 platform（默认 amd64）
4. **数据备份**：定期 `pg_dump` 导出数据库（可加 cron job）
5. **HTTPS**：测试阶段用 HTTP 即可；正式使用加 Let's Encrypt + certbot
6. **安全组**：SSH 端口建议限制来源 IP，避免暴力破解
7. **续费**：经济型 e 实例首购 99元/年，续费同价（阿里云活动期）；关注续费提醒
