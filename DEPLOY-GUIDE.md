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

### 内存分配（生产 voice/tts 全开版，2026-08-06 实测修正）

| 服务 | 内存 | 说明 |
|------|------|------|
| PostgreSQL 16 | ~512MB | |
| Redis 7 | ~128MB | |
| Spring Boot Backend | ~1GB | |
| **Voice（ASR=dashscope + SER=on）** | **6G** | emotion2vec+ 加载峰值 >4.2GB，2G/4G 会 OOM；SER=off 时 512M 即可 |
| TTS（CosyVoice 云端合成） | ~512MB | |
| 宿主 Nginx | ~64MB | |
| 系统 + swap | ~300MB | |
| **合计（生产全开）** | **约 9G** | 测试环境（不含 voice/tts）约 2G |

> 2C2G 建议开启 2GB swap 兜底；2C4G 无需 swap。**含 voice 的生产环境需 6G+ 内存**（`VOICE_MEMORY_LIMIT=6G`，见 .env.example）。

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

> ⚠️ 发布通道议决（AUD-060，2026-08-06）：**默认发布走 CD，deploy.sh 仅限紧急热修**（开发者主动调用）。
> 触发链路（OPS-005 修复，2026-08-07）：`git push main` → CI 四个 job 全绿 → `workflow_run` 触发 CD
> （cd.yml 不再使用跨 workflow needs——GitHub Actions 不支持，原配置导致 CD 从未运行）→
> 构建推送 GHCR 镜像 → SSH 部署后端/微服务 + rsync 前端 dist → E2E 冒烟验证。CI 失败则 CD 不触发。
>
> **增量发布（OPS-009，2026-08-07）**：CD 从服务器 `.cd-state-backend` / `.cd-state-frontend`（缺省回退 `.env` 镜像 tag）
> 读上次部署 SHA，`git diff` 按组件路径映射（backend/、backend/tts-service/、backend/voice-service/、frontend/*-h5/、
> scripts/sql/、deploy/），**只 pull/up/rsync 变更组件**；无部署历史时安全回退全量。
> 部署并发（AUD-009）：deploy（镜像）与 deploy-frontend（dist）拆组并行，实测从串行 20+ 分钟降到同时完成。
> 健壮性（OPS-008）：compose pull 3 次重试（GHCR 网络抖动兜底）、rsync 3 次重试、
> 健康检查走服务器本机 `127.0.0.1:18082/actuator/health`（绕过公网 nginx 路径层：`/actuator/health` 404、`/api/actuator/health` 403）。

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
cd /guju/mindsafe/deploy
cp .env.example .env
vim .env
```

必填项（完整模板见 `deploy/.env.example`，键名以 2026-08-06 切换后的新命名为准）：
```env
DB_PASSWORD=<强密码>
REDIS_PASSWORD=<强密码>
# 必填：prod profile 下缺失会 fail-fast 拒绝启动
JWT_SECRET=<openssl rand -base64 48 生成>
# 字段加密开关：试运行期保持 false（不启用终态，见 doing/64），无需配 ENCRYPTION_KEY
ENCRYPTION_ENABLED=false
# LLM 主模型（新命名，旧 LLM_API_KEY 已废弃）
LLM_PRIMARY_API_KEY=sk-your-deepseek-key
LLM_PRIMARY_BASE_URL=https://api.deepseek.com
LLM_PRIMARY_MODEL=deepseek-v4-pro
# LLM 备份模型（可选，留空 = 单模型）
LLM_BACKUP_API_KEY=
LLM_BACKUP_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
LLM_BACKUP_MODEL=qwen-plus
# 语音合成（阿里云百炼 CosyVoice，ASR=dashscope 时也用它）
DASHSCOPE_API_KEY=sk-ws-...
# ASR/SER：dashscope + emotion2vec+ 需 6G 内存（加载峰值 >4.2GB，2G/4G 会 OOM）
ASR_ENGINE=dashscope
SER_ENABLED=true
VOICE_MEMORY_LIMIT=6G
# 监护人同意（AUTH-040，PIPL §31）：生产保持 false（age<14 走 SMS 验证码闭环）；变量名必须带 MINDSAFE_ 前缀
MINDSAFE_CONSENT_TRIAL_AUTO_GRANT=false
```

> ⚠️ 2026-08-06 切换教训：`CONSENT_TRIAL_AUTO_GRANT`（缺 MINDSAFE_ 前缀）、`VOICE_MEM_LIMIT`、`LLM_API_KEY` 等旧变量名已废弃，配置不生效且日志无告警——只认 `.env.example` 中的键名。

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
| `DEPLOY_HOST` | 阿里云 ECS 公网 IP 或域名（AUD-004：CD 健康检查已加 `-k` 容忍 IP+域名证书场景，两者皆可） |
| `DEPLOY_USER` | `root`（或你创建的用户名） |
| `DEPLOY_SSH_KEY` | SSH 私钥文件全部内容 |
| `DEPLOY_KNOWN_HOSTS` | `ssh-keyscan <公网 IP>` 输出（ssh-key-action 校验主机指纹，防中间人） |
| `SMOKE_URL` | 部署后的访问地址（建议 `https://域名`；未设置时 CD 自动回退 `https://DEPLOY_HOST`） |
| `TEACHER_USER` / `TEACHER_PASS` | 冒烟测试用的教师账号（tests/e2e/smoke-test.sh） |
| `ADMIN_USER` / `ADMIN_PASS` | 冒烟测试用的管理员账号 |

> 冒烟测试缺少教师/管理员账号时，相关用例将告警跳过（smoke-test.sh 对空账号有保护），建议用首次部署种子数据中的账号。

### Step 7：首次部署（生产）

```bash
# 1. 初始化服务器环境（Docker、swap、目录结构；setup-server.sh 会完整同步 deploy/ 到 /guju/mindsafe/deploy/，AUD-002）
cd deploy && bash setup-server.sh

# 2. 配置 .env（见 Step 4）后启动生产栈（deploy/ 目录，compose 文件 docker-compose.prod.yml）
cd /guju/mindsafe/deploy
cp .env.example .env   # 首次执行 setup-server.sh 已自动生成，未生成时手动拷贝
vim .env
docker compose -f docker-compose.prod.yml up -d
bash ../service-manager.sh status   # 全部服务健康检查

# 3. 之后每次发版走 CD 主通道：git push main → GitHub Actions 自动构建镜像并 SSH 部署（见 §二）
#    ⚠️ 双部署通道议决（AUD-060，2026-08-06）：**CD 为主，deploy.sh 仅限紧急热修**。
#    仓库根目录的 deploy.sh（自动检测变更、选择性构建/上传/重启）仅在 CD 不可用
#    或需要绕过镜像构建直接源码重建时使用：export MINDSAFE_SERVER=root@<服务器IP> && ./deploy.sh
```

> ⚠️ **首次 prod 切换（AUD-003）**：若该服务器此前用 `docker-compose.test.yml` 启动过测试环境，
> 必须先将 test 环境 down 掉（两个 compose 共用 `container_name` 与 `mindsafe-internal` 网络，残留会报
> `container name already in use`）：
>
> ```bash
> cd /guju/mindsafe/deploy
> docker compose -f docker-compose.test.yml down
> docker ps -a | grep mindsafe-   # 确认仅剩 postgres/redis（数据卷保留），backend/nginx 无残留
> docker compose -f docker-compose.prod.yml up -d
> ```
>
> CD 流水线侧已做兜底（deploy/rollback job 部署前幂等 `docker rm -f` 同名应用容器，不触碰数据库/Redis），
> 但首次人工部署仍建议按上述步骤手动清理，避免 up 阶段报错。

> 2026-08-06 切换后生产统一走 `/guju/mindsafe/deploy/`（prod profile）；`docker-compose.test.yml` 仅用于轻量测试环境（不含 voice/tts），nginx 配置以宿主 nginx 为准。
> 部署完成后可手动跑冒烟（`tests/e2e/smoke-test.sh`，需教师/管理员账号）。

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
vim /guju/mindsafe/nginx/default.conf
# 将 server_name teacher.mindsafe.app 改为你的域名
docker compose -f docker-compose.test.yml restart nginx
```

### HTTPS 证书（生产必做，宿主 nginx 承载 443）

⚠️ **2026-08-06 切换后的架构事实**：80/443 由**宿主 nginx**（`/etc/nginx/nginx.conf`）直接监听，`deploy/docker-compose.prod.yml` 的 nginx 服务**未启用**（容器 Created）。所有 nginx 配置修改走宿主机文件 + `nginx -t && nginx -s reload`，不要改 compose nginx 配置期望生效。

证书签发（certbot，证书目录 `/etc/nginx/ssl/`，nginx.conf 引用 `ssl_certificate`）：

```bash
# 1. 安装 certbot（Ubuntu）
apt install -y certbot

# 2. 首次签发（standalone 模式，需要 80 端口空闲）
mkdir -p /etc/nginx/ssl
certbot certonly --standalone -d yun.gxjugu.com --agree-tos -m <你的邮箱>
# 将证书链拷贝到 nginx 读取路径（或同步修改 nginx.conf 的 ssl_certificate 路径）
cp /etc/letsencrypt/live/yun.gxjugu.com/fullchain.pem /etc/nginx/ssl/yun.gxjugu.com.pem
cp /etc/letsencrypt/live/yun.gxjugu.com/privkey.pem /etc/nginx/ssl/yun.gxjugu.com.key
nginx -t && nginx -s reload

# 3. 自动续期（续期后拷贝 + reload）
# 建议写 /etc/letsencrypt/renewal-hooks/deploy/copy-nginx-cert.sh 处理拷贝与 reload
```

> 域名变更时同步修改 `/etc/nginx/nginx.conf` 的 `server_name`/`ssl_certificate` 与 `.env` 的 `CORS_ORIGINS`。

### 端侧 ONNX 模型投放（语音唤醒/声纹，发布前端前必做）

学生端语音唤醒（whisper-tiny）与声纹登录（wespeaker）均为浏览器内推理，前端配置为 `SAME_ORIGIN` 同源加载（`/mindsafe/models/`）。**模型文件不入仓、不随构建传输**（deploy.sh rsync 固定 `--exclude 'models/'` 保护），由服务器侧维护：

```bash
# 方式一（推荐）：服务器上直接把模型放到部署目标目录
#   /guju/mindsafe/frontend/student-h5/dist/models/onnx-community/{whisper-tiny,wespeaker-voxceleb-resnet34-LM}
#   （模型目录与本地 frontend/student-h5/public/models 内容一致，约 50MB）
# 方式二：本地运行投放脚本后手动 rsync 到服务器（注意 deploy.sh 会自动排除，需手动传输）
bash deploy/scripts/prepare-models.sh   # 下载到 frontend/student-h5/public/models
rsync -avz frontend/student-h5/public/models/ root@<服务器>:/guju/mindsafe/frontend/student-h5/dist/models/

# 校验：模型文件经 /mindsafe/models/ 可 200 访问（浏览器 Network 面板逐请求确认）
```

> 2026-08-06 切换事实：`dist/models` 从旧部署目录 `cp` 迁移（50MB），`--exclude 'models/'` 保证后续 deploy.sh 不回删。若未投放，唤醒/声纹功能 404 失效（对话主路径不受影响）。

### 宿主 nginx 结构与前端路径（2026-08-06 切换教训固化）

**流量入口**：`/etc/nginx/nginx.conf` 的 `yun.gxjugu.com` 443 server 是生产主入口（`/api/`、`/ws/` 反代到 `127.0.0.1:18082`）；80 端口仅由 `nginx.conf` 的 301 server 处理。**`/etc/nginx/conf.d/*.conf` 未被 include（nginx.conf 只 `include mime.types`），在其中加配置不生效**——不要往 conf.d 里放站点配置。

**前端静态路径规则**（nginx.conf 443 server 内）：

| 前端 | nginx root/alias 必须指向 | 说明 |
|------|--------------------------|------|
| 学生端 `/mindsafe/` | `/guju/mindsafe/frontend/student-h5/dist/` | 含 sw.js/index.html/ort/models 特殊 location |
| 教师端 `/teacher/` | `/guju/mindsafe/frontend/teacher-web/dist/` | |
| 家长端 `/parent/` | `/guju/mindsafe/frontend/parent-h5/dist/` | |

> deploy.sh 已内置校验（部署后检查 nginx.conf 是否指向以上目录，未对齐会失败退出）。修改后必须 `nginx -t && nginx -s reload`；备份先 `cp nginx.conf nginx.conf.bak-$(date +%Y%m%d-%H%M)`。

### 切换与回归验证要点（2026-08-06 经验）

1. **切后端流量**：只改 `nginx.conf` 443 server 的 `proxy_pass`（18081→18082），两处（`/api/` 与 `/ws/`）；改完看 `nginx -t` + `tail /var/log/nginx/error.log` 的 `upstream` 字段确认真实落点
2. **验证新套特征**（防“老套活着时正常响应”的假验证）：登录后核对 JWT 签名算法（新套 HS512+iss/aud vs 老套 HS384）；SSE 首个事件 `type:emotion` 为新套特征
3. **curl 陷阱**：curl `127.0.0.1` 无 SNI/Host 头会落默认 server（假 404）——必须 `-H 'Host: yun.gxjugu.com' -k https://127.0.0.1/...` 或直连域名
4. **回滚通道**（见 design/doing/65 §2.4）：老套容器 `docker compose start` 恢复 18081；nginx 备份 `nginx.conf.bak-switch-20260806-1505`；数据双备份保留

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
| `.github/workflows/cd.yml` | 自动部署（Build → GHCR → SSH） |
| `deploy/docker-compose.test.yml` | 轻量测试环境（不含 voice/tts） |
| `deploy/docker-compose.prod.yml` | 完整生产环境（含 voice/tts） |
| `deploy/nginx/default.conf` | Nginx 双域名反向代理 |
| `deploy/setup-server.sh` | 服务器一键初始化（含 Docker 镜像加速；AUD-002 已对齐：完整同步 deploy/ 至 /guju/mindsafe/deploy/） |
| `deploy/.env.example` | 环境变量模板 |
| `deploy/scripts/prepare-models.sh` | 端侧 ONNX 模型投放（语音唤醒/声纹） |
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
4. **数据备份**：备份统一走 `deploy/backup.sh`（AUD-032：cron 已由 setup-server.sh 幂等接线 `0 2 * * * /guju/mindsafe/backup.sh >> /guju/mindsafe/logs/backup.log 2>&1`，脚本内部分层保留日 7/周 4/月 3）+ `deploy/restore.sh` 恢复。OD-007（2026-08-05）已移除原 docker-compose.prod.yml 中的 `db-backup` 定时容器（与 backup.sh 双写同一 volume 为真冗余）。**恢复演练**：在非生产库或低峰期执行 `./restore.sh daily/<备份名>.dump`（脚本会自动先打 safety snapshot，失败不覆盖原库），演练后核对 `pg_restore --list` 输出与关键表行数；每季度至少一次，演练记录追加到本文件「运维记录」节
5. **HTTPS**：测试阶段（docker-compose.test.yml）用 HTTP 即可；生产（docker-compose.prod.yml）已强制 TLS，首次部署先按「HTTPS 证书」节签发证书
6. **安全组**：SSH 端口建议限制来源 IP，避免暴力破解
7. **续费**：经济型 e 实例首购 99元/年，续费同价（阿里云活动期）；关注续费提醒
8. **端侧模型**：发布 student-h5 前必须先跑 `deploy/scripts/prepare-models.sh`（见「四、端侧 ONNX 模型投放」），否则语音唤醒/声纹不可用

## 九、配置变更流程

> 本系统采用分层配置架构（design/06 配置与外部服务依赖设计，源：design/57），改配置不改代码。

| 配置类型 | 文件位置 | 变更方式 |
|---------|---------|--------|
| 密钥/凭证 | `deploy/.env` | 改 .env + `docker compose up -d` 重启 |
| 声纹阈值/引导脚本/唤醒参数 | `backend/counseling-app/src/main/resources/application.yml` 的 `mindsafe.system-config` | 改 yml + 重新构建后端镜像 + 重启 |
| TTS 音色/方言/情感 | `backend/tts-service/config.yaml` | 改 yaml + 重新构建 tts-service 镜像 + 重启 |
| TTS 模型名 | `.env` 的 `DASHSCOPE_TTS_MODEL` | 改 .env + `docker compose up -d tts-service` |
| ASR/SER 模型名/情绪标签 | `backend/voice-service/config.yaml` | 改 yaml + 重新构建 voice-service 镜像 + 重启 |
| ASR 引擎切换 | `.env` 的 `ASR_ENGINE` | 改 .env + `docker compose up -d voice-service` |
| 前端运行时配置 | 自动从 `GET /api/v1/system/config` 拉取 | 后端配置变更后前端自动生效（5min 缓存） |

### 数据库迁移与回滚（ARCH-009 E-4，2026-08-06）

> 迁移位于 `backend/counseling-app/src/main/resources/db/migration/`（Flyway V1~V33）；回滚脚本位于同级 `rollback/`。

| 版本范围 | 回滚策略 | 说明 |
|---------|---------|------|
| V1~V27 | **不补 down 脚本，由 `deploy/restore.sh` 备份恢复承担** | 逐迁移 DDL 类型核验完成（见 design/doing/69 §3.4 完整清单）：纯结构 15 个（早期架构根基）、数据类不可逆 10 个（V3/V4/V5/V6/V8/V14/V17/V25/V26/V27：INSERT/UPDATE 改写）、扩展类 2 个（V15 pgcrypto / V24 vector，生产库删除风险高）。显式接受「不可逆迁移清单」 |
| V28~V33 | 已有 down 脚本（`rollback/` 6 个） | 维持现状，按现有 E2 演练体系执行 |
| **V34+（强制）** | **必须带 down 脚本 + 演练记录** | 发布检查项：新迁移 PR 未附 `rollback/Vxx__*.rollback.sql` 或未登记演练记录 → 禁止合并 |

**发布检查项（V34+ 迁移）**：
1. 迁移文件：`migration/Vxx__xxx.sql`
2. 回滚脚本：`rollback/Vxx__xxx.rollback.sql`（同 PR 提交）
3. 演练记录：scripts/ E2 体系登记回滚演练结果
4. 三项齐全方可合并发布；缺任一 → CI 人工门禁拦截

---

## 十、监控与告警

生产环境建议启用 Prometheus + Grafana + Alertmanager 监控栈：

```bash
# 在主服务运行后启动监控（独立 compose，不影响主服务）
# 前置：.env 中已设置 GRAFANA_PASSWORD（强密码，缺省 compose 直接 fail-fast）
cd deploy
docker compose -f docker-compose.monitoring.yml up -d
```

- **Prometheus**：仅 internal 网络可达（不公网暴露，`docker exec` 可查 UI）；抓取后端 `/actuator/prometheus` + tts/voice 服务 `/metrics`
- **Grafana**：`http://<服务器IP>:3002`（默认账号 admin，密码为 .env 的 `GRAFANA_PASSWORD`，关闭开放注册）
- **Alertmanager**：接收 Prometheus 告警，推送企业微信应用消息（仅 internal 网络可达）
- **数据源**：Grafana 已预配置 Prometheus 数据源（`deploy/monitoring/grafana/provisioning/`）

关键监控指标：
- `http_server_requests_seconds`：API 响应时间（后端）
- `hikaricp_connections_active`：数据库连接池（后端）
- `jvm_memory_used_bytes`：JVM 内存（后端）
- `tts_synthesize_requests_total` / `tts_engine_available`：TTS 合成量与引擎可用性
- `voice_analyze_requests_total` / `voice_asr_ready` / `voice_ser_ready`：语音分析量与引擎就绪状态

告警规则（`deploy/monitoring/alert-rules.yml`，P1-10）：
- 服务不可达（后端/TTS/语音，critical）：离线 1 分钟即告警
- 后端 5xx 错误率 > 5% / P95 延迟 > 2s（warning）
- TTS 全部引擎不可用（critical）与合成失败率 > 50%（warning）
- 语音分析失败/超时率 > 50%（warning）

告警通道：Alertmanager → 企业微信应用消息（复用 .env 的 `WECOM_CORP_ID`/`WECOM_AGENT_ID`/`WECOM_SECRET`），
接收人 `WECOM_ALERT_TO_USER`（企微 userid，`@all` 需全员权限）；未配置时告警仅留存 Alertmanager 不推送（日志可见）。
业务告警（SlaEscalationScanner）仍走企业微信 Webhook（`ALERT_WECOM_WEBHOOK_URL`），两者独立。
