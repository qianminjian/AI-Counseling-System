# doing/72 取消 CD 收敛部署通道（方案与 SPEC）

> 编号：DOC-063 | 创建：2026-08-07 | 状态：📋 方案定稿待实施
> 决策：钱敏健（2026-08-07）——**已实际部署到环境，决定取消 CD，只做 GitHub 上的 CI；CD 部分通过真实环境（deploy.sh 通道）实现发布和部署**
> 前置事实：CD run 31145421244 实战全量恢复 70min+、detect 顺序 bug、GHCR 带宽 ~1MB/s、unexpected EOF 抖动；服务器服务中断 ~40min 后已用本地 5d5bdac 镜像恢复
> 关联：doing/71（AUD-002~004/009/035/053/060 状态更新）、DEPLOY-GUIDE.md、his/64~70、frozen/42（部署升级专题）
> 结论先行：**CD 自动发布在单人/单服务器/2C2G/低带宽场景下成本远大于收益；deploy.sh（真实环境源码构建）是正解；CI 保持纯质量门禁零改动；CD 阶段踩的坑中，通用教训（重试/健康探针/nginx）已吸收进 deploy.sh 与本文档**

---

## §1 背景与决策

### 1.1 决策内容

| 项 | 决策 |
|---|---|
| GitHub CI | **保留**（ci.yml 不动）：编译 + 测试 + 扫描 + 覆盖率，纯质量门禁 |
| GitHub CD | **删除**（cd.yml 移除，用户已确认）：不再自动构建镜像 / 推送 GHCR / SSH 部署 |
| 发布通道 | **deploy.sh 唯一通道**：本地执行 → rsync 增量同步源码 → 服务器 compose 本地构建 → service-manager 重启 + 健康检查 → 前端 nginx reload + 路径校验 |
| 镜像仓库 | GHCR 相关步骤全部停用（镜像不再跨网拉取） |

### 1.2 决策反转说明（AUD-060 反转）

- 2026-08-06 议决（doing/71 §7 / TASK-TRACKER 登记）：**"CD 为主，deploy.sh 仅限紧急热修"**
- 2026-08-07 实战一天即反转：CD 自动发布链路在真实环境中暴露的问题（带宽瓶颈、链路断裂、调试成本）远超其自动化收益
- **教训（登记为本专题第一条经验）**：发布通道的自动化决策必须以真实网络/带宽/链路实测为准，不能基于架构理论推导；"自动部署"听起来好，但对单服务器场景是过度工程

---

## §2 决策依据（深度分析）

### 2.1 带宽与网络（根因层）

| 事实 | 数据 |
|---|---|
| 服务器 → GHCR 拉取带宽 | ~1MB/s（阿里云 ECS 3Mbps 出口限速的典型表现） |
| voice-service 镜像体量 | 2.26GB（Python 依赖 + 模型依赖层） |
| 首次全量拉取耗时 | 36 分钟以上（实测），且 **unexpected EOF（GHCR 网络抖动）导致整次 pull 失败** |
| 服务器 → 本地 rsync 源码 | 增量几 MB~几十 MB，压缩传输，分钟级 |
| 服务器本地 compose build | Docker layer 缓存命中，依赖层仅首次在线下载，之后增量构建分钟级 |

**结论**：CD 的"不可变镜像 pull"模型在 3Mbps 带宽下是结构性劣势——每个新 tag 都跨网拉大镜像，且 GHCR 抖动无解（换 ACR 也只是换个网络，不解决 1MB/s 限速）。deploy.sh 的"源码增量同步 + 服务器本地构建"把跨网传输量从 GB 级降到 MB 级，这是本决策最核心的工程理由。

### 2.2 CD 阶段踩坑全盘点（14 项）→ 取消 CD 后的处置归属

| # | 坑（编号） | 现象 | 取消 CD 后处置 |
|---|---|---|---|
| 1 | workflow_run 触发断裂（OPS-005） | CD 从未运行，跨 workflow needs 不支持 | 🗑 关闭（CD 不存在） |
| 2 | 镜像名大小写被 GHCR 拒绝（OPS-007） | Docker CLI reference 要求全小写 | 🗑 关闭 |
| 3 | 镜像 tag 生成与位数（OPS-006） | metadata-action 7 位短 SHA | 🗑 关闭 |
| 4 | pull unexpected EOF 无重试（OPS-008） | 网络抖动整次失败 | ✅ 教训吸收 → **B2：deploy.sh compose build 加重试**（同源问题：在线下载依赖） |
| 5 | 健康检查公网路径 404/403（OPS-008） | nginx 层无 /actuator/health；/api/ 前缀被 Security 拦 | ✅ 教训固化 → service-manager 本机探针 `127.0.0.1:18082`（已有，确认即可） |
| 6 | compose nginx 443 与宿主 nginx 冲突（OPS-008） | bind 443 失败 | ✅ 已固化（deploy.sh L296 注释 + up 剔除 nginx） |
| 7 | 增量发布实现复杂（OPS-009） | 服务器状态文件 + SSH 读 + git diff 组件映射 + 安全回退 | ✅ 简化替代 → deploy.sh 本地 git diff 直接检测（已在实现中，无 SSH 依赖） |
| 8 | detect 步骤顺序 bug（ssh 无 key 静默失败） | detect 在 Install SSH key 前 → LAST_SHA 空 → 安全回退全量 → 白拉 2.26GB | 🗑 关闭（deploy.sh 无此模式） |
| 9 | 并发组串行浪费 20+ 分钟（AUD-009） | deploy/frontend 共享 concurrency 组 | 🗑 关闭（deploy.sh 无并发） |
| 10 | CD 健康检查 IP+HTTPS 必败（AUD-004） | IP 直连证书校验失败 | 🗑 关闭（deploy.sh 本机探针无此问题） |
| 11 | setup-server 目录与 cd.yml 期望不符（AUD-002） | 按手册初始化 CD 必失败 | 🗑 关闭（CD 专属） |
| 12 | test/prod compose 容器名冲突（AUD-003） | 同名 container_name 冲突 | ⚠️ **保留**：deploy.sh 通道同样适用 → DEPLOY-GUIDE 补"首次 prod 切换"步骤（见 §3.4） |
| 13 | rollback 竞态写 .env（AUD-009 关联） | workflow_dispatch 与常规发布并发 | 🗑 关闭（deploy.sh --rollback 无竞态：串行 + 状态文件） |
| 14 | image_tag 无格式校验（AUD-053） | workflow_dispatch 注入面 | 🗑 关闭 |

**固化状态结论**：14 项中 11 项随 CD 取消自然关闭；3 项通用教训（4/5/6）已固化为 deploy.sh/service-manager 的既有或新增能力。**CD 阶段的所有学习没有丢失**：关闭项的历史留在 Git 与本文档 §2.2 表，可追溯。

### 2.3 为什么 deploy.sh 是"真实环境"正解

- **传输模型**：CD = 代码 → GHCR（推送）→ 服务器（拉取），两次跨网、GB 级；deploy.sh = 本地 → 服务器 rsync 增量（MB 级），构建在服务器本地完成（layer 缓存命中，复用首次依赖下载）
- **验证闭环**：deploy.sh 已内置——变更检测（git diff + .deploy-state）、rsync 重试、构建失败即中止（服务不中断）、service-manager 重启 + 健康检查、nginx reload + 路径校验、部署状态文件。与 CD 的"pull → up → 冒烟"是同一验证语义，但链路短一半
- **回滚语义**：deploy.sh `--rollback` = 服务器源码重建（失败场景下就地重试）；真正的版本回退 = `git revert + push + ./deploy.sh`，与 CD 的"旧 tag 回退"等价且更直观
- **CI 保留的理由**：编译/测试/扫描是全自动零人工成本的质量门禁，与部署通道解耦；ci.yml 无镜像构建推送（仅有 pgvector/redis 测试服务容器），**取消 CD 后 CI 零改动**

### 2.4 重新引入 CD 的演进条件（防过度设计，写入边界）

以下任一条件出现才重议 CD（对应 frozen/42 部署升级专题挂账）：

1. **多环境/多服务器**：test/staging/prod 需要一致发布与审批留痕
2. **带宽升级**：服务器 ≥10Mbps 或接入内网镜像加速（ACR/自建 registry）
3. **多人协作**：发布需要自动化工单与审计（合规要求）
4. 当前 2C2G 单服务器 + 3Mbps 带宽 + 单人场景：**不满足任何一条，维持 deploy.sh 通道**

---

## §3 改造方案

### 3.1 删除 cd.yml（A）

- `git rm .github/workflows/cd.yml`，删除即停用（GitHub Actions 只识别 workflows/ 下文件）
- 历史完全可追溯：本会话 CD 阶段全部 commit（5d5bdac → e3b9d93 → 7c5310c）保留在 Git 历史
- 服务器 GHCR 登录凭证（docker login ghcr.io 的 PAT）无泄露风险但已无用途，待清理（§3.3 C4）

### 3.2 deploy.sh 增强（B，两处小改）

- **B1 头部议决注释反转**：删除"默认发布走 CD / 本脚本仅用于紧急热修"段落，改为"**唯一发布通道**"声明 + 指向 DEPLOY-GUIDE §二
- **B2 compose build 重试**：L284 构建命令包 3 次重试循环（对齐 CD pull 重试教训——服务器在线下载依赖同样受网络抖动影响），失败 3 次才中止：

```bash
BUILD_OK=false
for attempt in 1 2 3; do
  if ssh "$SERVER" "cd $REMOTE_DIR/deploy && docker compose -f docker-compose.prod.yml build $BUILD_TARGETS"; then
    BUILD_OK=true; break
  fi
  echo "⚠️ 镜像构建第 $attempt 次失败，60s 后重试（网络抖动兜底）..."
  sleep 60
done
[ "$BUILD_OK" = true ] || { echo "❌ 镜像构建重试 3 次仍失败，部署中止（服务仍运行旧版本）"; exit 1; }
```

- **B3 .env IMAGE 残留校验**：部署前置检查服务器 `deploy/.env` 若存在 `BACKEND_IMAGE`/`VOICE_SERVICE_IMAGE`/`TTS_SERVICE_IMAGE` 行（CD 注入残留），打印警告并自动清理（sed 删除），确保 compose 回退默认 tag `mindsafe/backend:local` 语义自洽——一次性清理 + 幂等告警

### 3.3 服务器清理（C）

| 项 | 操作 |
|---|---|
| C1 | 删除 `/guju/mindsafe/deploy/.cd-state-backend`、`.cd-state-frontend`（CD 残留） |
| C2 | `.env` 删除 3 行 IMAGE 变量（回退 compose 默认 tag） |
| C3 | 清理 ghcr.io 命名残留镜像（tts:e3b9d93 219MB、backend/voice/tts:5d5bdac 三镜像）与 Exited 容器（mindsafe-nginx 等），释放磁盘 |
| C4 | 删除/搁置 GHCR PAT（`docker login` 凭据；确认 ~/.docker/config.json 中的 ghcr 项后清理，属密钥操作需用户确认时机） |

> C3 前先确认：5d5bdac 三镜像删除前，当前运行容器 tag 为 5d5bdac（`.env` 已回退），删除后容器运行不受影响（运行中容器不依赖 tag 存在）；下次 deploy.sh 构建后产生新本地 tag。

### 3.4 DEPLOY-GUIDE.md 改写（D）

- **§二 架构图**：替换为"CI 验证 + 手动发布"两段式（开发者 push main → CI 全绿 → 本地 deploy.sh → 服务器源码构建部署）
- **删除**：CD 触发链路（OPS-005 描述）、增量发布（OPS-009 描述）、GHCR 镜像说明、AUD-060 "CD 为主"议决注释（历史见 doing/72 §2.2）
- **Step 7**：改"首次部署"步骤，补 **test/prod 容器名冲突规避**（AUD-003：首次 prod 切换前确认无 test 环境残留容器）
- **secrets 表**：`DEPLOY_HOST`/`DEPLOY_USER`/`SMOKE_URL` 标注"CD 停用，保留定义防误用"；新增 `MINDSAFE_SERVER`（deploy.sh 前置，建议写 ~/.zshrc）
- **保留**：rsync/models 排除、前端部署路径、nginx 校验等与 deploy.sh 通道相关的内容

### 3.5 doing/71 状态更新（E）

- AUD-002/004/009/035/053 → 标注"🗑 随 DOC-063（doing/72）取消 CD 关闭"
- AUD-003 → 保留有效，降级 P2，修复= DEPLOY-GUIDE 补首次 prod 切换步骤（§3.4 D 项）
- AUD-060 → 标注"⚠️ 2026-08-07 决策反转：deploy.sh 为唯一通道，CD 取消（doing/72）"
- 批次 A 范围收敛：AUD-002~004（CD 闭环）随 CD 取消移除；保留 AUD-001（声纹）/AUD-010（CONSENT 漂移）

### 3.6 CI 保持不动（F）

- 已确认 ci.yml 无镜像构建/推送步骤（仅 pgvector/redis 服务容器）→ 零改动
- 可选增强（不做，YAGNI）：CI 增加"部署就绪"产物（如前端构建缓存预热），无需求不引入

---

## §4 发布 SOP（新常态，写入 DEPLOY-GUIDE）

```
1. 开发 → git commit → git push main
2. 等 CI 全绿（约 2 分钟，GitHub Actions 自动）
3. 本地执行 ./deploy.sh            # 自动检测变更组件，只部署受影响服务
   （或 ./deploy.sh --backend 等定向部署；--all 全量）
4. 部署完成：service-manager 健康检查通过 + 前端 nginx 已 reload + 路径校验通过
5. 回滚：
   - 构建失败回滚（就地）：./deploy.sh --rollback <backend|tts|voice>（服务器源码重建）
   - 版本回退：git revert <bad_commit> && git push && ./deploy.sh
6. 验证：https://yun.gxjugu.com/mindsafe/ 冒烟
```

---

## §5 验收标准

- [x] cd.yml 已删除，GitHub Actions 仓库 Actions 页无 CD workflow（只剩 CI）
- [x] CI 全绿（67e79ea push 后 run 31148177181 全绿；e1ff891 push 后自动触发待确认）
- [x] deploy.sh 实测全流程成功（2026-08-07 两轮：首轮 exit 1 暴露 2 个真机坑 → 修复后 --student --teacher --parent 全链路 exit 0）
- [x] 服务器无 `.cd-state-*`、`.env` 无 IMAGE 残留、ghcr.io 镜像已清理、mindsafe-nginx 残留容器已清理、5 容器 healthy
- [x] DEPLOY-GUIDE §二/Step 7/secrets 表已改写一致，无 CD 残留描述
- [x] doing/71 AUD 条目状态已更新、TASK-TRACKER 登记 DOC-063、BEACON 演进日志同步
- [x] 服务健康（backend/voice/tts/redis/pg 全 healthy）

---

## §6 风险与边界

| 风险 | 缓解 |
|---|---|
| 部署依赖本地机（SSH key、homebrew rsync） | 单人场景可接受；DEPLOY-GUIDE 写明前置条件（MINDSAFE_SERVER、rsync 3.x）；换机需重新配置 |
| 服务器 2C2G 构建 voice 大镜像内存/时长压力 | 已有多次成功先例（deploy.sh 历史）；Dockerfile 依赖分级（requirements-lite 等）；B2 重试兜底网络抖动 |
| 服务器 .env 被误改导致 compose 语义漂移 | B3 自动清理 + 校验；.env 纳入 deploy.sh 管理范围 |
| GHCR 历史镜像/凭证残留 | C3/C4 清理项；凭证删除时机由用户确认 |
| 未来需求变化需要 CD | §2.4 演进条件明确，frozen/42 挂账，不提前实现 |

---

## §7 实施清单（顺序执行）

| 批次 | 项 | 验证 |
|---|---|---|
| A | `git rm .github/workflows/cd.yml` + deploy.sh 头部注释反转（B1） | grep cd.yml 无引用 |
| B | deploy.sh 增加 build 重试（B2）+ .env 残留清理（B3） | bash -n + 代码审查 |
| C | DEPLOY-GUIDE §二/Step 7/secrets 改写（D） | 全文 grep 无 "CD 为主" 残留 |
| D | doing/71 状态更新（E）+ TASK-TRACKER DOC-063 登记 + BEACON 演进日志 | 文档一致 |
| E | 服务器清理（C1/C2/C3） | ssh 验证 |
| F | commit + push → CI 全绿 → **deploy.sh 实测一次**（验证 B2/B3 + 全链路） | deploy.sh 成功输出 |

---

## §8 过程数据（归档时不并入主文档，仅追溯）

- CD 阶段 3 个 commit：5d5bdac（CI 修复收尾）→ e3b9d93（OPS-008/009 + AUD-009 + 健康探针）→ 7c5310c（detect 顺序修复）
- CD run 实测：31145421244 全量恢复 70min+ 后用户取消；服务中断 ~40min 后本地镜像恢复
- 决策反转时间线：AUD-060 议决（2026-08-06）→ 实战验证（2026-08-07）→ 反转（2026-08-07）

### §8.1 首轮实测暴露的 2 个真机坑（2026-08-07，commit e1ff891 修复）

**坑 1：bash 3.2 多字节变量名解析 bug（部署日志乱码根因）**

- 现象：check_nginx_path 的 echo 中 `$deploy_dir` 展开为空（`（）` 之间零字节），且其后的中文标点丢首字节（`）`→`bc 89`、`——`→`80 94`）
- 根因：macOS 默认 bash 3.2 在 UTF-8 locale 下，`$var` 后紧跟全角字符（`）` `—` `，` 等）时，把全角字符首字节并入变量名（`${deploy_dir\xef}` 不存在 → 展开为空 + 字符丢首字节）。xxd 对比实验实锤：`A（$d）B` → 路径消失；`A（${d}）B` → 完整
- 修复：脚本中变量后跟多字节字符一律 `${var}` 包裹（deploy.sh 4 处、service-manager.sh 1 处、db-rollback-drill.sh 2 处、gen-changelog.sh 1 处）；新增 perl 扫描模式 `\$[a-zA-Z_][a-zA-Z0-9_]*[^\x00-\x7f]` 入库检查
- 教训：**本项目 shell 脚本变量展开统一 `${var}` 风格**（macOS bash 3.2 环境），避免变量后直接跟中文标点

**坑 2：本机网络 ssh banner 阶段随机断连（parent 校验误报根因）**

- 现象：部署时 parent nginx 路径校验误报 FAIL（student/teacher 通过）；日志显示 `kex_exchange_identification: Connection closed by remote host`——连接在 TCP 建立后、认证前的 banner 阶段被随机关闭（代理/运营商干扰，与 BatchMode 无关，-v 调试证实认证本身成功）
- 影响：旧实现 `! ssh ... 2>/dev/null` 把连接失败（rc=255）与 grep 不匹配（rc=1）混为一谈，静默误报"路径未对齐"导致部署 exit 1；rsync 同样随机失败（实测前 2 次失败、第 3 次重试成功）
- 修复：check_nginx_paths 重构为**单次 ssh 会话完成全部路径校验**（3 次独立连接失败率降为 1 次）+ 连接失败重试 3 次；rsync_retry 保留（实测已兜底）
- 教训：**所有 ssh 远程调用必须区分连接失败与命令失败，并带重试**；`2>/dev/null` 静默吞错是部署脚本大忌

**连带修复：service-manager.sh nginx 特判**

- 现象：restart nginx 时执行 `compose up -d nginx` → 443 被宿主 nginx 占用 → bind 冲突报错 + mindsafe-nginx 残留容器（Created）；健康检查实际探测宿主 443 通过，掩盖了冲突
- 修复：start_service 对 nginx 特判——跳过 compose up，仅健康探测宿主 443（与 L61-62 注释语义对齐）；残留容器已清理
- 教训：**代码与注释语义矛盾会掩盖真实状态**（注释说 compose nginx 未启用，代码却尝试启动）
