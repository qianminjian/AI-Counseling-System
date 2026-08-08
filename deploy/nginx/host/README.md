# deploy/nginx/host/ —— 宿主 nginx 配置版本化位（DA-13）

生产架构事实（2026-08-06 切换后）：**80/443 由宿主 nginx（/etc/nginx/nginx.conf）直接监听**，
prod compose 的 nginx 服务已删除（DA-13 议决 a+b）；
`default-ssl.conf` 为死资产已清除，`default.conf`/`security-headers.conf` 仍服务 test/dev compose（活资产）。

本目录是**宿主 nginx 配置的唯一仓库事实源**，由 deploy.sh 的 `sync_host_nginx` 步骤在每次
部署时同步到宿主 `/etc/nginx/`（`nginx -t` 门禁 + 通过后 `nginx -s reload`）。

## 首次回填（一次性运维动作，配置纳入版本控制）

```bash
# 从宿主机拉取当前生效配置（含 nginx.conf 及 conf.d/ 等被 include 的文件）
mkdir -p deploy/nginx/host/conf.d
scp $MINDSAFE_SERVER:/etc/nginx/nginx.conf deploy/nginx/host/
scp "$MINDSAFE_SERVER:/etc/nginx/conf.d/"*.conf deploy/nginx/host/conf.d/ 2>/dev/null || true

# 回填后本地冒烟（可选）：docker run --rm -v "$PWD:/etc/nginx:ro" nginx:alpine nginx -t
# 提交入库后，后续修改一律改仓库 → ./deploy.sh 发布（不再直接改宿主文件）
```

## 约束

- 目录内**只放宿主 nginx 配置**（nginx.conf 主文件 + conf.d/ 片段）；README.md 被上传逻辑排除
- 上传为覆盖同步（不 --delete），宿主其他配置（如 frp 等）不受影响
- `nginx -t` 失败即中止部署（不 reload），坏配置不会生效；已上传文件需**人工回滚**——备份 `nginx.conf.bak-<时间戳>` 仅覆盖 nginx.conf 主文件，conf.d/ 片段请对照仓库手动恢复
