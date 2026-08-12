# 审计报告 11 - 部署与配置

- **审计时间**：2026-08-12
- **审计范围**：`deploy/`（14 项：.env.example 138 行 / 4 个 docker-compose / nginx 4 文件 + host/ / monitoring 4 文件 + grafana / scripts 5 脚本 / init / 备份恢复） + `deploy.sh`（579 行）+ `service-manager.sh`（13.5KB）+ `DEPLOY-GUIDE.md` + `.github/workflows/`（ci.yml 350 行 + prepare-models.yml 45 行）
- **方法**：git log 热点分析（doing/80/81 部署审计、doing/87 RUNTIME）+ 全量读取 ci.yml、docker-compose.yml/prod.yml、.env.example、nginx/default.conf、deploy.sh 门禁段、prepare-models.yml + 环境变量透传链核对（compose → .env.example → 应用消费）+ 冻结决策核对（只读，未改动任何文件）

## 1. 板块概况

**部署体系**（多通道 + 版本化 + 审计闭环）：

- **通道矩阵**：docker-compose.yml（本机一键）/ docker-compose.prod.yml（生产，SPRING_PROFILES_ACTIVE=prod 启用 fail-fast）/ docker-compose.monitoring.yml（Prometheus + Alertmanager + Grafana）/ docker-compose.test.yml + deploy.sh（服务器通道：git 同步校验 → Flyway 版本唯一性 → 变更检测 → 选择性构建 → rsync 降速自愈 → 模型门禁 → 宿主 nginx 版本化同步）。
- **生产拓扑**：backend/tts/voice 全走 internal 网络，公网唯一入口 nginx；backend 仅绑定 127.0.0.1:18082（D-01 铁律）；Actuator 只暴露 health（nginx default.conf:131-138）；公开认证端点 nginx 层限流（AUD-030，:1-3/:98-114）；安全头 include 修复嵌套 location 继承缺陷（:12-14）。
- **密钥管理**：.env.example 全 `<CHANGE_ME_*>` 占位符 + 必填/可选分级注释（:3-6）；prod profile 应用层 fail-fast（JWT/加密密钥缺失拒绝启动，prod.yml:57-58）；字段加密默认关闭（ENCRYPTION_ENABLED=false，商业化阶段 COMP-008 再启用）。
- **运维审计**：deploy.sh trap 两段式出口（dm_finish_deploy + dm_audit_run，:132-136）、DOC-078 日志回归分析（R1-R6）、备份/恢复脚本、deploy-audit/deploy-metrics/deploy-lib 纯函数库（DA-11 收敛，均有配套测试）。

## 2. 热点与风险初判

- **doing/80/81**：部署日志审计与回归分析（DOC-077/078）——部署通道已全审计化。
- **doing/87 RUNTIME-004**：两 Python 服务 Redis 覆盖键直连（prod.yml:146-149/:179-182，fail-open）。
- **doing/93 S-020**：nginx 公开端点限流公共片段（proxy-backend-common.conf）。
- **风险初判**：①prod compose 探测默认值硬编码公网 IP（见 P1-1）；②开发 compose 默认凭据字面量（见 P2-1）；③试点期 SMS=logging 与验证码回显组合成"演示认证"事实（已冻结决策，见 §5 联动标注）。

## 3. 发现清单

### P0（架构级）
**未发现**。部署配置与冻结决策高度一致：D-01 本机绑定、internal 网络隔离、Actuator 收敛、限流/安全头、密钥 CHANGE_ME 占位 + 应用层 fail-fast、部署前 git/Flyway 门禁、回滚与备份齐全。未发现分层违规或配置与代码不一致。

### P1（模块级）

| 编号 | 位置 | 问题描述 | 建议方案 | 预期收益 | 删除测试判断 |
|---|---|---|---|---|---|
| P1-1 | deploy/docker-compose.prod.yml:66 | **服务探测默认值硬编码公网 IP**：`MINDSAFE_MONITORING_SERVICE_PROBES_NGINX: ${...:-http://116.8.109.229}`——生产 compose 中唯一以具体 IP 作默认值的项（其余默认值均为域名/空/占位符）。换服务器部署时若未在 .env 覆盖，nginx 健康探测会静默指向旧主机地址，产生假健康；且公网 IP 直接写进配置库属敏感信息外露 | 默认值改为空或 `http://host.docker.internal` 占位，真实地址收敛进 .env（探测失败显式告警而非静默指向旧地址）；同步修正 DEPLOY-GUIDE 对应说明 | 一致性：配置库零具体 IP、换机部署无静默假健康；leverage：nginx 探测是监控栈关键依赖 | 保留：deploy-lib/nginx 校验测试不受影响，新增默认值契约断言可选 |

### P2（局部）

| 编号 | 位置 | 问题描述 | 建议方案 |
|---|---|---|---|
| P2-1 | deploy/docker-compose.yml:14/:101 | 开发 compose 默认凭据字面量 `mindsafe2026` / `mindsafe-jwt-secret-change-in-production`——本机开发可接受，但若被误用于外部可达环境即默认口令风险 | 注释显式标注"禁止用于非本机环境"，或在启动前校验 `DB_PASSWORD` 是否为默认值并告警 |

## 4. 改进候选排序

- **Strong**：P1-1 探测地址默认值收敛（改动 2 行 + .env.example 说明，消除假健康与 IP 外露）。
- **Worth exploring**：无（部署体系其余质量已高）。
- **Speculative**：P2-1 默认凭据告警（低收益，可选）。

## 5. 设计一致性核对

| 冻结决策 | 实现核对 | 结论 |
|---|---|---|
| D-01（design/04 §3.1）：后端仅绑定 127.0.0.1，安全组误开也无法绕过 nginx | prod.yml:52-55（`127.0.0.1:18082:8080` + 注释） | ✅ 一致 |
| AUD-030：公开认证端点 nginx 限流 + 声纹双闸 | default.conf:1-3（10r/s zone）、:98-114（voiceprint/parent-auth/auth 前缀 + burst=20） | ✅ 一致 |
| P1-DEP：安全头 include 统一（修复 add_header 继承缺陷） | default.conf:12-14 + 嵌套 location 显式 include（:26/:33/:46/:143） | ✅ 一致 |
| doing/93 S-020：限流公共片段 | proxy-backend-common.conf 挂载 + 三处限流 location include | ✅ 一致 |
| DA-06：端侧模型门禁随 deploy.sh 生效 | deploy.sh:341-374（模型投放门禁段） | ✅ 一致 |
| DA-13 议决 b：宿主 nginx 配置版本化同步 | deploy.sh:379-383 sync_host_nginx + deploy/nginx/host/nginx.conf + verify-nginx-host-test.sh | ✅ 一致 |
| DOC-077/078：部署审计 + 回归分析（R1-R6）+ 主动修复 | deploy.sh:114-136（trap 两段式 + dm_audit_run）+ deploy/scripts/deploy-audit.sh | ✅ 一致 |
| E-5：模型投放自动化（manifest 校验和 + --verify 门禁） | prepare-models.yml + deploy/scripts/prepare-models.sh（workflow_dispatch 手动触发，DOC-063 取消 CD 后无自动投放） | ✅ 一致 |
| P3-31：容器非 root 运行 | docker-compose.yml:75（模型缓存挂载至 /home/appuser/.cache） | ✅ 一致 |
| 设计修正（2026-08-06）：试点期 SMS_PROVIDER=logging（未启用 aliyun） | prod.yml:91-94 + .env.example:48-52——已冻结决策，不视为违规；**但与板块09 P1-1（toc 验证码回显）组合构成"试点期演示认证"事实，汇总时建议作为上线门禁项登记** | ✅ 一致（含联动风险标注） |

## 6. 修复建议

- **P0**：无。
- **P1**：P1-1 探测地址默认值收敛（建议进入集中修复，2 行改动 + 文档，消除假健康风险）。
- **P2**：可选。
- **汇总引用**：SMS=logging × 验证码回显的"演示认证"组合与板块09 P1-1 共享同一修复专题（上线门禁登记）；P2-1 默认凭据提示可与板块10 P1-1（错误细节泄漏）归入"红线域配置/信息管控"。
